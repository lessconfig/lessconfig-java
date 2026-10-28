package com.lessconfig;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Thread-safe LessConfig client for Java 8. Configuration reads subscribe automatically
 * and return defensive copies of the last successfully loaded values. Metadata reads always contact
 * the server. All collection results are unmodifiable, non-null lists, including when empty.
 * Except for {@link #getStatus()} and {@link #close()}, operations after closure throw
 * {@link IllegalStateException}. Invalid identifiers throw {@link IllegalArgumentException}.
 * HTTP, protocol and network failures throw {@link LessConfigException} on the calling thread;
 * background failures are reported through {@link StatusListener}.
 */
public interface LessConfigClient extends AutoCloseable {
    /**
     * Creates a builder with default settings.
     *
     * @return a new, independent builder
     */
    static LessConfigClientBuilder builder() {
        return new LessConfigClientBuilder();
    }

    /**
     * Starts the watcher thread if it has not been started. Calling this method is optional:
     * configuration reads also start watching. With no subscriptions, the thread waits without HTTP polling.
     *
     * @throws IllegalStateException if closed or stopped after a permanent watch failure
     */
    void start();

    /**
     * Reads a cached configuration, loading and subscribing on first use.
     * A missing key in an existing environment remains subscribed for future creation.
     * Cached values remain readable during background failures. Initial loading does not emit a change event.
     *
     * @param project     case-sensitive project slug
     * @param environment case-sensitive environment slug
     * @param key         case-sensitive configuration key
     * @return a defensive copy, or null if the key, environment or project does not exist
     * @throws LessConfigException      if initial loading fails for a reason other than a missing resource
     * @throws IllegalArgumentException if any identifier is invalid
     */
    @Nullable Config getConfig(String project, String environment, String key);

    /**
     * Reads the cached environment configuration list and subscribes to the entire environment,
     * including future keys. The returned beans are defensive copies, ordered as on the server.
     *
     * @param project     case-sensitive project slug
     * @param environment case-sensitive environment slug
     * @return an unmodifiable list, empty for an existing environment with no configurations; never null
     * @throws LessConfigException if initial loading fails, including a missing project/environment
     */
    @NonNull List<Config> getConfigs(String project, String environment);

    /**
     * Reads current environment metadata directly from the server.
     *
     * @param project     case-sensitive project slug
     * @param environment case-sensitive environment slug
     * @return environment metadata, or null if it or its project is absent
     * @throws LessConfigException if the request fails for another reason
     */
    @Nullable Environment getEnvironment(String project, String environment);

    /**
     * Lists current environments directly from the server, in server order.
     *
     * @param project case-sensitive project slug
     * @return an unmodifiable list, empty when no environments are visible; never null
     * @throws LessConfigException if the request fails, including a missing project
     */
    @NonNull List<Environment> getEnvironments(String project);

    /**
     * Reads current project metadata directly from the server.
     *
     * @param project case-sensitive project slug
     * @return project metadata, or null if absent
     * @throws LessConfigException if the request fails for another reason
     */
    @Nullable Project getProject(String project);

    /**
     * Lists current projects visible to the credential, in server order.
     *
     * @return an unmodifiable list, empty when no projects are visible; never null
     * @throws LessConfigException if the server, transport or response decoding fails
     */
    @NonNull List<Project> getProjects();

    /**
     * Registers a listener for all configurations already or subsequently subscribed by this client.
     * Registration alone does not open a watch scope. Duplicate registrations are ignored.
     *
     * @param listener configuration-change listener; never null
     */
    void addListener(ConfigListener listener);

    /**
     * Registers a listener and synchronously loads/subscribes the specified key.
     * No initial event is synthesized. Registration is rolled back if initialization fails.
     *
     * @param project     case-sensitive project slug
     * @param environment case-sensitive environment slug
     * @param key         case-sensitive configuration key
     * @param listener    configuration-change listener; never null
     * @throws LessConfigException if subscription initialization fails, including a missing ancestor
     */
    void addListener(String project, String environment, String key, ConfigListener listener);

    /**
     * Registers a listener and synchronously loads/subscribes the whole environment, including future keys.
     *
     * @param project     case-sensitive project slug
     * @param environment case-sensitive environment slug
     * @param listener    configuration-change listener; never null
     * @throws LessConfigException if subscription initialization fails
     */
    void addListener(String project, String environment, ConfigListener listener);

    /**
     * Removes every registration of the same listener instance. An in-flight callback may finish.
     * Removing listeners does not evict configuration caches or unsubscribe their scopes.
     *
     * @param listener previously registered listener; never null
     */
    void removeListener(ConfigListener listener);

    /**
     * Registers an observer for watcher health. Duplicate registrations are ignored.
     * Registration does not replay the current status; query {@link #getStatus()} if needed.
     *
     * @param listener status observer; never null
     */
    void addStatusListener(StatusListener listener);

    /**
     * Removes a status observer. An in-flight callback may finish.
     *
     * @param listener previously registered observer; never null
     */
    void removeStatusListener(StatusListener listener);

    /**
     * Returns the current watcher lifecycle/health state. This method is also valid after closure.
     *
     * @return current state; never null
     */
    WatchStatus getStatus();

    /**
     * Returns the most recent background failure, cleared on successful recovery.
     *
     * @return last background failure, or null when no failure is outstanding
     */
    @Nullable LessConfigException getWatchError();

    /**
     * Cancels HTTP requests, releases cached state and stops the watcher. Repeated calls have no effect.
     * An already-running user callback may finish; this method does not wait for user callback completion.
     */
    @Override
    void close();
}
