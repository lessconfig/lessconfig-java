package com.lessconfig;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default implementation with one daemon watcher and an in-memory cache per client.
 */
public class DefaultLessConfigClient implements LessConfigClient {
    private static final Logger LOG = Logger.getLogger(DefaultLessConfigClient.class.getName());
    private final LessConfigApi api;
    private final LessConfigApi watchApi;
    private final Duration readTimeout;
    private final Duration watchTimeout;
    private final Duration watchReadTimeout;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object updateLock = new Object();
    private final CopyOnWriteArrayList<Registration> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<StatusListener> statusListeners = new CopyOnWriteArrayList<>();
    private final Object statusLock = new Object();
    private final Deque<StatusEvent> statusEvents = new ArrayDeque<>();
    private volatile WatchStatus status = WatchStatus.IDLE;
    private boolean dispatchingStatus;
    private final Deque<List<ConfigChange>> notifications = new ArrayDeque<>();
    private final LongPollWatcher watcher;
    private volatile State state = new State(Collections.emptyMap(), null);
    private volatile @Nullable LessConfigException watchError;
    private volatile boolean watchStopped;
    // Kept until its complete refresh succeeds. Failed batches never acknowledge the watermark.
    private @Nullable Pending pending;

    /**
     * Creates a client using the builder defaults without starting network activity.
     *
     * @param endpoint complete HTTP(S) API base URL, including /api/v1
     * @param token    nonempty Bearer credential
     * @throws IllegalArgumentException if endpoint or token is invalid
     */
    public DefaultLessConfigClient(String endpoint, String token) {
        this(endpoint, token, Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(60),
                Duration.ofSeconds(90), Duration.ofSeconds(1), Duration.ofSeconds(30));
    }

    /**
     * Creates a client from validated builder settings without starting work.
     *
     * @param endpoint         complete API URL
     * @param token            Bearer credential
     * @param connectTimeout   connection establishment timeout
     * @param readTimeout      ordinary request read timeout
     * @param watchTimeout     server long-poll timeout
     * @param watchReadTimeout client long-poll read timeout
     * @param retryInitial     initial background retry delay
     * @param retryMaximum     maximum background retry delay
     */
    DefaultLessConfigClient(String endpoint, String token, Duration connectTimeout, Duration readTimeout,
                            Duration watchTimeout, Duration watchReadTimeout, Duration retryInitial, Duration retryMaximum) {
        this.api = new LessConfigApi(endpoint, token, connectTimeout, readTimeout);
        this.watchApi = new LessConfigApi(endpoint, token, connectTimeout, readTimeout);
        this.readTimeout = readTimeout;
        this.watchTimeout = watchTimeout;
        this.watchReadTimeout = watchReadTimeout;
        watcher = new LongPollWatcher(new LongPollWatcher.Task() {
            /** {@inheritDoc} */
            @Override
            public void run() {
                watchOnce();
            }

            /** {@inheritDoc} */
            @Override
            public boolean onError(LessConfigException error) {
                return reportError(error);
            }
        }, retryInitial, retryMaximum);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
        ensureOpen();
        if (watchStopped) {
            throw new IllegalStateException("Watching stopped after a permanent error; create a new client", watchError);
        }
        watcher.start();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @Nullable Config getConfig(String project, String environment, String key) {
        Objects.requireNonNull(key, "key");
        ConfigId id = new ConfigId(project, environment, key);
        try {
            List<Config> configs = subscribe(id);
            for (Config config : configs) {
                if (key.equals(config.getKey())) {
                    return copy(config);
                }
            }
            return null;
        } catch (LessConfigException e) {
            if (missingAncestor(e)) {
                return null;
            }
            throw e;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull List<Config> getConfigs(String project, String environment) {
        List<Config> result = new ArrayList<>();
        for (Config config : subscribe(new ConfigId(project, environment, null))) {
            result.add(copy(config));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Loads a missing scope and starts watching, or returns its current cached snapshot.
     *
     * @param requested validated key or environment selection
     * @return snapshot containing the requested scope; never null
     * @throws LessConfigException if the scope cannot be initialized and read
     */
    private List<Config> subscribe(ConfigId requested) {
        ensureOpen();
        List<Config> cached = find(state, requested);
        if (cached != null) {
            return cached;
        }
        if (watchStopped) {
            throw new IllegalStateException("Watching stopped after a permanent error; create a new client", watchError);
        }
        boolean selectionChanged = false;
        try {
            synchronized (updateLock) {
                ensureOpen();
                cached = find(state, requested);
                if (cached != null) {
                    return cached;
                }
                try {
                    initialize(select(state.configs, requested), true);
                    selectionChanged = true;
                } catch (LessConfigException error) {
                    if (!missingAncestor(error) || state.configs.isEmpty()) {
                        throw error;
                    }
                    Map<ConfigId, List<Config>> live = survivingScopes(state);
                    if (live.keySet().equals(state.configs.keySet())) {
                        // The requested scope itself is absent; leave existing subscriptions untouched.
                        throw error;
                    }
                    state = new State(live, null);
                    pending = null;
                    selectionChanged = true;
                    // A deleted old scope must not make an unrelated, existing new scope appear absent.
                    initialize(select(live, requested), false);
                }
                cached = find(state, requested);
            }
        } finally {
            if (selectionChanged) {
                watchApi.cancel();
                watcher.wake();
            }
        }
        transition(WatchStatus.WATCHING, null);
        if (!closed.get()) {
            watcher.start();
            watcher.wake();
        }
        return cached == null ? Collections.emptyList() : cached;
    }

    /**
     * Expands a selection, merging key subscriptions covered by a new environment subscription.
     *
     * @param existing  current selected scopes
     * @param requested newly requested scope
     * @return expanded selection with a placeholder for the new scope
     */
    private static Map<ConfigId, List<Config>> select(Map<ConfigId, List<Config>> existing, ConfigId requested) {
        Map<ConfigId, List<Config>> selected = new LinkedHashMap<>(existing);
        selected.keySet().removeIf(requested::covers);
        selected.put(requested, Collections.emptyList());
        return selected;
    }

    /**
     * Finds an exact or covering environment subscription in an immutable snapshot.
     *
     * @param state     cache snapshot to inspect
     * @param requested requested key or environment
     * @return cached entries, or null if the scope is not subscribed
     */
    private static @Nullable List<Config> find(State state, ConfigId requested) {
        for (Map.Entry<ConfigId, List<Config>> entry : state.configs.entrySet()) {
            if (entry.getKey().covers(requested)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Publishes all selected configurations and their initialization watermark atomically.
     * The caller holds updateLock; listeners are queued and never invoked under this lock.
     *
     * @param selected complete scope selection after expansion or recovery
     * @param catchUp  whether to preserve pending server events for the old selection
     * @throws LessConfigException if initialization, catch-up or any configuration read fails
     */
    private void initialize(Map<ConfigId, List<Config>> selected, boolean catchUp) {
        LessConfigApi.WatchResult initial = api.watch(selected.keySet(), null, watchTimeout, readTimeout);
        List<ConfigChange> priorChanges = Collections.emptyList();
        if (catchUp && state.revision != null && !state.configs.isEmpty() && initial.revision > state.revision) {
            // Preserve outstanding server notifications for old scopes when expanding the selection.
            // This catch-up may wait up to one second if only unrelated scopes changed.
            try {
                priorChanges = api.watch(state.configs.keySet(), state.revision, Duration.ofSeconds(1),
                        readTimeout.compareTo(Duration.ofSeconds(2)) < 0 ? Duration.ofSeconds(2) : readTimeout).changes;
            } catch (LessConfigException e) {
                if (!resetRequired(e) && !missingAncestor(e)) {
                    throw e;
                }
            }
        }
        Map<ConfigId, List<Config>> loaded = new LinkedHashMap<>();
        for (ConfigId id : selected.keySet()) {
            loaded.put(id, load(id));
        }
        ensureOpen();
        state = new State(loaded, initial.revision);
        pending = null;
        if (!priorChanges.isEmpty()) {
            notifications.add(priorChanges);
        }
    }

    /**
     * Reads a complete selected scope from the server.
     *
     * @param id validated scope
     * @return configuration entries, empty when an explicitly selected key is missing
     * @throws LessConfigException if the request fails, including an absent ancestor
     */
    private List<Config> load(ConfigId id) {
        if (id.getKey() == null) {
            return api.getConfigs(id.getProject(), id.getEnvironment());
        }
        Config config = api.getConfig(id);
        return config == null ? Collections.emptyList() : Collections.singletonList(config);
    }

    /**
     * Performs one watch/recovery cycle and only acknowledges fully applied batches.
     */
    private void watchOnce() {
        deliverNotifications();
        if (closed.get()) {
            return;
        }
        State current = state;
        if (current.configs.isEmpty()) {
            transition(WatchStatus.IDLE, null);
            watcher.awaitWork();
            return;
        }
        if (current.revision == null) {
            try {
                synchronized (updateLock) {
                    if (state == current) {
                        initialize(current.configs, false);
                    }
                }
                transition(WatchStatus.WATCHING, null);
            } catch (LessConfigException e) {
                if (!missingAncestor(e)) {
                    throw e;
                }
                synchronized (updateLock) {
                    if (state == current) {
                        pruneMissing(current);
                    }
                }
                reportError(e);
            }
            return;
        }
        LessConfigApi.WatchResult result;
        try {
            Pending retry;
            synchronized (updateLock) {
                retry = pending;
            }
            result = retry != null && retry.base == current ? retry.result
                    : watchApi.watch(current.configs.keySet(), current.revision, watchTimeout, watchReadTimeout);
        } catch (LessConfigException e) {
            synchronized (updateLock) {
                if (state != current || closed.get() || e instanceof HttpTransport.CancelledException) {
                    return;
                }
                if (resetRequired(e)) {
                    state = new State(current.configs, null);
                    pending = null;
                } else if (missingAncestor(e)) {
                    pruneMissing(current);
                } else {
                    throw e;
                }
            }
            reportError(e);
            return;
        }
        synchronized (updateLock) {
            if (closed.get() || state != current) {
                return;
            }
            pending = new Pending(current, result);
            Map<ConfigId, List<Config>> refreshed = new LinkedHashMap<>(current.configs);
            for (ConfigId id : current.configs.keySet()) {
                boolean affected = false;
                for (ConfigChange change : result.changes) {
                    if (change.affects(id)) {
                        affected = true;
                        break;
                    }
                }
                if (affected) {
                    try {
                        refreshed.put(id, load(id));
                    } catch (LessConfigException e) {
                        if (!missingAncestor(e)) {
                            throw e;
                        }
                        refreshed.remove(id);
                    }
                }
            }
            ensureOpen();
            boolean changedScopes = !refreshed.keySet().equals(current.configs.keySet());
            state = new State(refreshed, changedScopes ? null : result.revision);
            pending = null;
            if (!result.changes.isEmpty()) {
                notifications.add(result.changes);
            }
        }
        transition(state.configs.isEmpty() ? WatchStatus.IDLE : WatchStatus.WATCHING, null);
        deliverNotifications();
    }

    /**
     * Reloads live scopes and removes subscriptions whose ancestors no longer exist.
     *
     * @param current snapshot to reconcile while updateLock is held
     * @throws LessConfigException if a live scope cannot be reloaded
     */
    private void pruneMissing(State current) {
        Map<ConfigId, List<Config>> live = survivingScopes(current);
        ensureOpen();
        state = new State(live, null);
        pending = null;
    }

    /**
     * Loads all still-existing selected scopes without publishing partial state.
     *
     * @param current immutable selection to inspect
     * @return loaded live scopes, excluding missing ancestors
     * @throws LessConfigException if any live scope cannot be read
     */
    private Map<ConfigId, List<Config>> survivingScopes(State current) {
        Map<ConfigId, List<Config>> live = new LinkedHashMap<>();
        for (ConfigId id : current.configs.keySet()) {
            try {
                live.put(id, load(id));
            } catch (LessConfigException error) {
                if (!missingAncestor(error)) {
                    throw error;
                }
            }
        }
        return live;
    }

    /**
     * Identifies server errors requiring a fresh global watch watermark.
     *
     * @param e server failure
     * @return true for revision_expired or revision_ahead
     */
    private static boolean resetRequired(LessConfigException e) {
        return (e.getStatusCode() == 410 && "revision_expired".equals(e.getCode()))
                || (e.getStatusCode() == 409 && "revision_ahead".equals(e.getCode()));
    }

    /**
     * Identifies a missing project/environment without confusing a missing key or API route.
     *
     * @param e server failure
     * @return true for a recognized ancestor HTTP 404
     */
    private static boolean missingAncestor(LessConfigException e) {
        return e.getStatusCode() == 404 && ("project_not_found".equals(e.getCode())
                || "environment_not_found".equals(e.getCode()));
    }

    /**
     * Classifies a background failure and notifies health observers outside cache locks.
     *
     * @param error failure from a watch or cache-refresh request
     * @return true when the watcher should retry, false after a permanent failure or closure
     */
    private boolean reportError(LessConfigException error) {
        if (closed.get()) {
            return false;
        }
        int code = error.getStatusCode();
        boolean retry = code == 0 || code >= 500 || code == 408 || code == 429
                || missingAncestor(error) || resetRequired(error);
        if (!retry) {
            watchStopped = true;
        }
        transition(retry ? WatchStatus.RETRYING : WatchStatus.STOPPED, error);
        return retry;
    }

    /**
     * Publishes ordered status notifications without calling user code under internal locks.
     * Reentrant transitions are queued for the current dispatcher.
     *
     * @param next  new watcher status
     * @param error associated failure, or null for a healthy/lifecycle transition
     */
    private void transition(WatchStatus next, @Nullable LessConfigException error) {
        synchronized (statusLock) {
            if (status == WatchStatus.CLOSED || (status == next && error == null)
                    || (watchStopped && next != WatchStatus.STOPPED && next != WatchStatus.CLOSED)) {
                return;
            }
            status = next;
            watchError = error;
            statusEvents.add(new StatusEvent(next, error));
            if (dispatchingStatus) {
                return;
            }
            dispatchingStatus = true;
        }
        while (true) {
            StatusEvent event;
            synchronized (statusLock) {
                event = statusEvents.poll();
                if (event == null) {
                    dispatchingStatus = false;
                    return;
                }
            }
            for (StatusListener listener : statusListeners) {
                try {
                    listener.onStatusChange(event.status, event.error);
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "LessConfig status listener failed", e);
                }
            }
            if (event.status == WatchStatus.CLOSED) {
                statusListeners.clear();
            }
        }
    }

    /**
     * Delivers queued raw server batches outside cache locks and isolates listener failures.
     */
    private void deliverNotifications() {
        while (!closed.get()) {
            List<ConfigChange> batch;
            synchronized (updateLock) {
                batch = notifications.poll();
            }
            if (batch == null) {
                return;
            }
            for (Registration registration : listeners) {
                if (closed.get()) {
                    return;
                }
                List<ConfigChange> matching = new ArrayList<>();
                for (ConfigChange change : batch) {
                    if (registration.scope == null || change.affects(registration.scope)) {
                        matching.add(change);
                    }
                }
                if (!matching.isEmpty()) {
                    try {
                        registration.listener.onChange(Collections.unmodifiableList(matching));
                    } catch (RuntimeException e) {
                        LOG.log(Level.WARNING, "LessConfig change listener failed", e);
                    }
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @Nullable Environment getEnvironment(String project, String environment) {
        ensureOpen();
        return api.getEnvironment(project, environment);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull List<Environment> getEnvironments(String project) {
        ensureOpen();
        return api.getEnvironments(project);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @Nullable Project getProject(String project) {
        ensureOpen();
        return api.getProject(project);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull List<Project> getProjects() {
        ensureOpen();
        return api.getProjects();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addListener(ConfigListener listener) {
        add(null, listener);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addListener(String project, String environment, String key, ConfigListener listener) {
        Objects.requireNonNull(key, "key");
        add(new ConfigId(project, environment, key), listener);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addListener(String project, String environment, ConfigListener listener) {
        add(new ConfigId(project, environment, null), listener);
    }

    /**
     * Registers an observer and initializes its scope, rolling registration back on failure.
     *
     * @param scope    selected resource, or null to observe the client's current/future subscriptions
     * @param listener configuration-change observer
     * @throws LessConfigException if a new scope cannot be initialized
     */
    private void add(@Nullable ConfigId scope, ConfigListener listener) {
        ensureOpen();
        Registration registration = new Registration(scope, Objects.requireNonNull(listener, "listener"));
        if (listeners.addIfAbsent(registration) && scope != null) {
            try {
                subscribe(scope);
            } catch (RuntimeException e) {
                listeners.remove(registration);
                throw e;
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeListener(ConfigListener listener) {
        ensureOpen();
        Objects.requireNonNull(listener, "listener");
        listeners.removeIf(registration -> registration.listener == listener);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addStatusListener(StatusListener listener) {
        ensureOpen();
        statusListeners.addIfAbsent(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeStatusListener(StatusListener listener) {
        ensureOpen();
        statusListeners.remove(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WatchStatus getStatus() {
        return status;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @Nullable LessConfigException getWatchError() {
        ensureOpen();
        return watchError;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            watcher.close();
            watchApi.close();
            api.close();
            listeners.clear();
            synchronized (updateLock) {
                state = new State(Collections.emptyMap(), null);
                pending = null;
                notifications.clear();
            }
            transition(WatchStatus.CLOSED, null);
        }
    }

    /**
     * Checks operational lifecycle. @throws IllegalStateException if the client is closed
     */
    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Client is closed");
        }
    }

    /**
     * Detaches a configuration bean from the internal snapshot.
     *
     * @param source cached configuration
     * @return independent mutable bean
     */
    private static Config copy(Config source) {
        Config copy = new Config();
        copy.setKey(source.getKey());
        copy.setValue(source.getValue());
        copy.setType(source.getType());
        copy.setRawType(source.getRawType());
        copy.setDescription(source.getDescription());
        copy.setRevision(source.getRevision());
        return copy;
    }

    /**
     * Immutable health notification waiting for serialized delivery.
     */
    private static final class StatusEvent {
        final WatchStatus status;
        final @Nullable LessConfigException error;

        StatusEvent(WatchStatus status, @Nullable LessConfigException error) {
            this.status = status;
            this.error = error;
        }
    }

    /**
     * Atomically published cache and global progress; null progress requires reinitialization.
     */
    private static final class State {
        final Map<ConfigId, List<Config>> configs;
        final @Nullable Long revision;

        State(Map<ConfigId, List<Config>> configs, @Nullable Long revision) {
            this.configs = Collections.unmodifiableMap(new LinkedHashMap<>(configs));
            this.revision = revision;
        }
    }

    /**
     * An unacknowledged watch batch retained across failed cache refresh attempts.
     */
    private static final class Pending {
        final State base;
        final LessConfigApi.WatchResult result;

        Pending(State base, LessConfigApi.WatchResult result) {
            this.base = base;
            this.result = result;
        }
    }

    /**
     * Listener identity and optional resource filter.
     */
    private static final class Registration {
        final @Nullable ConfigId scope;
        final ConfigListener listener;

        Registration(@Nullable ConfigId scope, ConfigListener listener) {
            this.scope = scope;
            this.listener = listener;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Registration)) {
                return false;
            }
            Registration that = (Registration) other;
            return Objects.equals(scope, that.scope) && listener == that.listener;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int hashCode() {
            return Objects.hashCode(scope) * 31 + System.identityHashCode(listener);
        }
    }
}
