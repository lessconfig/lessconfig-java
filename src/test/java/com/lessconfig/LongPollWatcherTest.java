package com.lessconfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.lessconfig.TestServer.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral contract tests for watch synchronization, concurrency and failure recovery.
 */
class LongPollWatcherTest {
    private TestServer server;
    private DefaultLessConfigClient client;

    /**
     * Creates a fresh protocol fixture and client so each concurrency scenario owns its resources.
     *
     * @throws Exception if fixture setup, HTTP I/O or bounded asynchronous waiting fails
     */
    @BeforeEach
    void setup() throws Exception {
        server = new TestServer();
        client = server.client();
    }

    /**
     * Closes the client before the server to exercise orderly watcher cancellation.
     */
    @AfterEach
    void teardown() {
        client.close();
        server.close();
    }

    /**
     * Verifies callback reads see refreshed values and receive immutable server records without synthesized payloads.
     *
     * @throws Exception if fixture setup, HTTP I/O or bounded asynchronous waiting fails
     */
    @Test
    void refreshesCacheBeforeDeliveringImmutableServerBatch() throws Exception {
        BlockingQueue<List<ConfigChange>> batches = new LinkedBlockingQueue<>();
        AtomicReference<String> callbackValue = new AtomicReference<>();
        client.addListener("payment", "prod", "application.yml", changes -> {
            callbackValue.set(client.getConfig("payment", "prod", "application.yml").getValue());
            batches.add(changes);
        });
        server.put("application.yml", "new", "yaml", "updated");
        List<ConfigChange> changes = batches.poll(5, TimeUnit.SECONDS);
        assertNotNull(changes);
        assertEquals("new", callbackValue.get());
        assertEquals(1, changes.size());
        ConfigChange change = changes.get(0);
        assertEquals("payment", change.getProject());
        assertEquals("prod", change.getEnvironment());
        assertEquals("application.yml", change.getKey());
        assertEquals("updated", change.getType());
        assertEquals("config", change.getResource());
        assertEquals(2, change.getRevision());
        assertThrows(UnsupportedOperationException.class, changes::clear);
        await(() -> server.requests.stream().anyMatch(r -> Long.valueOf(2).equals(r.revision)));
    }

    /**
     * Checks negative caching still observes later creation, deletion and restoration of the same key.
     *
     * @throws Exception if fixture setup, HTTP I/O or bounded asynchronous waiting fails
     */
    @Test
    void watchesInitiallyMissingKeysAndHandlesDeleteAndRestore() throws Exception {
        BlockingQueue<ConfigChange> events = new LinkedBlockingQueue<>();
        client.addListener("payment", "prod", "future", changes -> events.addAll(changes));
        assertNull(client.getConfig("payment", "prod", "future"));
        int reads = server.reads.get();
        assertNull(client.getConfig("payment", "prod", "future"));
        assertEquals(reads, server.reads.get());
        server.put("future", "one", "text", "created");
        assertEquals("created", events.poll(5, TimeUnit.SECONDS).getType());
        assertEquals("one", client.getConfig("payment", "prod", "future").getValue());
        server.delete("future");
        assertEquals("deleted", events.poll(5, TimeUnit.SECONDS).getType());
        assertNull(client.getConfig("payment", "prod", "future"));
        server.put("future", "two", "text", "restored");
        assertEquals("restored", events.poll(5, TimeUnit.SECONDS).getType());
        assertEquals("two", client.getConfig("payment", "prod", "future").getValue());
    }

    /**
     * Verifies environment subscriptions include future keys and serve key reads from the shared refreshed cache.
     *
     * @throws Exception if fixture setup, HTTP I/O or bounded asynchronous waiting fails
     */
    @Test
    void environmentCacheIncludesFutureKeysAndReceivesBatches() throws Exception {
        BlockingQueue<List<ConfigChange>> batches = new LinkedBlockingQueue<>();
        client.addListener("payment", "prod", batches::add);
        synchronized (server.monitor) {
            server.put("a", "1", "number", "created");
            server.put("b", "true", "boolean", "created");
        }
        List<ConfigChange> changes = batches.poll(5, TimeUnit.SECONDS);
        assertNotNull(changes);
        assertEquals(2, changes.size());
        assertEquals(3, client.getConfigs("payment", "prod").size());
        int reads = server.reads.get();
        assertEquals("1", client.getConfig("payment", "prod", "a").getValue());
        assertEquals(reads, server.reads.get(), "key reads reuse the environment cache");
        assertTrue(server.requests.stream().filter(r -> r.revision != null)
                .allMatch(r -> r.scopes.size() == 1 && r.scopes.get(0).equals("payment/prod")));
    }

    /**
     * Injects an update after watch initialization and confirms the original watermark still delivers its invalidation.
     *
     * @throws Exception if fixture setup, HTTP I/O or bounded asynchronous waiting fails
     */
    @Test
    void doesNotMissChangesBetweenInitializationAndFirstRead() throws Exception {
        BlockingQueue<ConfigChange> changes = new LinkedBlockingQueue<>();
        server.afterInitialize = () -> server.put("application.yml", "during-init", "yaml", "updated");
        client.addListener(changes::addAll);
        assertEquals("during-init", client.getConfig("payment", "prod", "application.yml").getValue());
        assertNotNull(changes.poll(5, TimeUnit.SECONDS));
        assertTrue(server.requests.stream().anyMatch(r -> Long.valueOf(1).equals(r.revision)));
    }

    /**
     * Checks failed refresh preserves the successful cache and old watermark until retry can apply the complete update.
     *
     * @throws Exception if fixture setup, HTTP I/O or bounded asynchronous waiting fails
     */
    @Test
    void retriesFailedRefreshWithoutAcknowledgingOrNotifying() throws Exception {
        BlockingQueue<LessConfigException> errors = new LinkedBlockingQueue<>();
        BlockingQueue<ConfigChange> changes = new LinkedBlockingQueue<>();
        AtomicReference<String> valueAtFailure = new AtomicReference<>();
        client.addListener(changes::addAll);
        client.addStatusListener((status, error) -> {
            if (error != null) {
                valueAtFailure.set(client.getConfig("payment", "prod", "application.yml").getValue());
                errors.add(error);
            }
        });
        client.getConfig("payment", "prod", "application.yml");
        server.readFailures.set(100);
        server.put("application.yml", "new", "yaml", "updated");
        assertEquals(503, errors.poll(5, TimeUnit.SECONDS).getStatusCode());
        assertEquals("# 原文\nport: 8080\n", valueAtFailure.get());
        assertTrue(changes.isEmpty());
        assertFalse(server.requests.stream().anyMatch(r -> Long.valueOf(2).equals(r.revision)));
        server.readFailures.set(0);
        assertNotNull(changes.poll(5, TimeUnit.SECONDS));
        assertEquals("new", client.getConfig("payment", "prod", "application.yml").getValue());
        await(() -> client.getWatchError() == null);
    }

    /**
     * Fails the second read in a multi-key refresh and verifies neither partial values nor an advanced checkpoint are published.
     *
     * @throws Exception if fixture setup, HTTP I/O or bounded asynchronous waiting fails
     */
    @Test
    void publishesBatchAtomicallyWhenOneOfSeveralReadsFails() throws Exception {
        server.put("second", "old", "text", "created");
        client.getConfig("payment", "prod", "application.yml");
        client.getConfig("payment", "prod", "second");
        BlockingQueue<LessConfigException> errors = new LinkedBlockingQueue<>();
        client.addStatusListener((status, error) -> {
            if (error != null) {
                errors.add(error);
            }
        });
        AtomicInteger refreshReads = new AtomicInteger();
        server.beforeRead = () -> {
            if (refreshReads.incrementAndGet() >= 2) {
                server.readFailures.set(100);
            }
        };
        synchronized (server.monitor) {
            server.put("application.yml", "new1", "yaml", "updated");
            server.put("second", "new2", "text", "updated");
        }
        assertNotNull(errors.poll(5, TimeUnit.SECONDS));
        assertEquals("# 原文\nport: 8080\n", client.getConfig("payment", "prod", "application.yml").getValue());
        assertEquals("old", client.getConfig("payment", "prod", "second").getValue());
        server.beforeRead = null;
        server.readFailures.set(0);
        await(() -> "new2".equals(client.getConfig("payment", "prod", "second").getValue()));
        assertEquals("new1", client.getConfig("payment", "prod", "application.yml").getValue());
    }

    /**
     * Verifies 410/409 recovery reinitializes before rereading cached scopes and subsequently resumes normal updates.
     *
     * @throws Exception if fixture setup, HTTP I/O or bounded asynchronous waiting fails
     */
    @Test
    void recoversExpiredAndAheadRevisionsByInitializingThenReloading() throws Exception {
        client.getConfig("payment", "prod", "application.yml");
        for (TestServer.Response error : new TestServer.Response[]{
                TestServer.error(410, "revision_expired"), TestServer.error(409, "revision_ahead")}) {
            int initializations = server.initializations.get();
            server.pollErrors.add(error);
            await(() -> server.initializations.get() > initializations);
            server.put("application.yml", "revision-" + initializations, "yaml", "updated");
            await(() -> ("revision-" + initializations).equals(client.getConfig("payment", "prod", "application.yml").getValue()));
        }
        assertTrue(server.reads.get() >= 3);
    }

    /**
     * Checks an empty watch response advances global progress without redundant configuration reads.
     *
     * @throws Exception if fixture setup, HTTP I/O or bounded asynchronous waiting fails
     */
    @Test
    void advancesHeartbeatWatermarkWithoutReloadingConfiguration() throws Exception {
        client.getConfig("payment", "prod", "application.yml");
        synchronized (server.monitor) {
            server.revision = 50;
            server.monitor.notifyAll();
        }
        await(() -> server.requests.stream().anyMatch(r -> Long.valueOf(50).equals(r.revision)));
        assertEquals(1, server.reads.get());
    }

    /**
     * Verifies adding a scope initializes the new selection and preserves pending changes for an existing listener.
     *
     * @throws Exception if fixture setup, HTTP I/O or bounded asynchronous waiting fails
     */
    @Test
    void reinitializesExpandedScopesAndKeepsExistingListenerChanges() throws Exception {
        BlockingQueue<ConfigChange> changes = new LinkedBlockingQueue<>();
        client.addListener(changes::addAll);
        client.getConfig("payment", "prod", "application.yml");
        server.put("second", "2", "number", "created");
        server.put("application.yml", "updated", "yaml", "updated");
        client.getConfig("payment", "prod", "second");
        assertEquals(2, server.initializations.get());
        await(() -> server.requests.stream().anyMatch(r -> r.scopes.size() == 2 && r.revision != null));
        await(() -> changes.stream().anyMatch(c -> c.getKey().equals("application.yml")));
        server.put("second", "3", "number", "updated");
        await(() -> "3".equals(client.getConfig("payment", "prod", "second").getValue()));
    }

    /**
     * Checks listener failures are isolated and removed listeners no longer receive subsequent batches.
     *
     * @throws Exception if fixture setup, HTTP I/O or bounded asynchronous waiting fails
     */
    @Test
    void listenerFailuresAndRemovalDoNotBreakOtherListeners() throws Exception {
        AtomicInteger removedCalls = new AtomicInteger();
        BlockingQueue<ConfigChange> changes = new LinkedBlockingQueue<>();
        ConfigListener removed = batch -> removedCalls.incrementAndGet();
        client.addListener(batch -> {
            throw new IllegalStateException("test listener failure");
        });
        client.addListener(changes::addAll);
        client.addListener(removed);
        client.removeListener(removed);
        client.getConfig("payment", "prod", "application.yml");
        server.put("application.yml", "new", "yaml", "updated");
        assertNotNull(changes.poll(5, TimeUnit.SECONDS));
        assertEquals(0, removedCalls.get());
        server.put("application.yml", "newer", "yaml", "updated");
        await(() -> "newer".equals(client.getConfig("payment", "prod", "application.yml").getValue()));
    }

    /**
     * Verifies a permanent authorization error stops polling, reports health separately and preserves last successful values.
     *
     * @throws Exception if fixture setup, HTTP I/O or bounded asynchronous waiting fails
     */
    @Test
    void stopsOnAuthorizationFailureButKeepsSuccessfulCache() throws Exception {
        BlockingQueue<LessConfigException> errors = new LinkedBlockingQueue<>();
        client.addStatusListener((status, error) -> {
            if (error != null) {
                errors.add(error);
            }
        });
        server.pollErrors.add(TestServer.error(403, "environment_read_denied"));
        client.getConfig("payment", "prod", "application.yml");
        assertEquals(403, errors.poll(5, TimeUnit.SECONDS).getStatusCode());
        assertNotNull(client.getConfig("payment", "prod", "application.yml"));
        assertEquals(403, client.getWatchError().getStatusCode());
        assertThrows(IllegalStateException.class, client::start);
        assertThrows(IllegalStateException.class, () -> client.getConfig("payment", "prod", "new-key"));
    }

    /**
     * Checks a transient HTTP failure retries from the unacknowledged watermark and eventually applies later updates.
     *
     * @throws Exception if fixture setup, HTTP I/O or bounded asynchronous waiting fails
     */
    @Test
    void retriesTransientPollErrorsFromTheSameCheckpoint() throws Exception {
        server.pollErrors.add(TestServer.error(503, "unavailable"));
        client.getConfig("payment", "prod", "application.yml");
        await(() -> server.requests.stream().filter(r -> Long.valueOf(1).equals(r.revision)).count() >= 2);
        server.put("application.yml", "recovered", "yaml", "updated");
        await(() -> "recovered".equals(client.getConfig("payment", "prod", "application.yml").getValue()));
    }

    /**
     * Checks project deletion evicts descendant cache entries and preserves the nullable fields of the server deletion record.
     *
     * @throws Exception if fixture setup, HTTP I/O or bounded asynchronous waiting fails
     */
    @Test
    void removesDeletedAncestorCacheAndDeliversItsRawRecord() throws Exception {
        BlockingQueue<ConfigChange> changes = new LinkedBlockingQueue<>();
        client.addListener("payment", "prod", "application.yml", changes::addAll);
        assertTrue(server.pollEntered.await(5, TimeUnit.SECONDS));
        server.deleteAncestor(true);
        ConfigChange change = changes.poll(5, TimeUnit.SECONDS);
        assertNotNull(change);
        assertEquals("project", change.getResource());
        assertNull(change.getEnvironment());
        assertNull(change.getKey());
        assertNull(client.getConfig("payment", "prod", "application.yml"));
    }

    /**
     * Verifies an ancestor 404 prunes stale cache state even when no deletion event can be retrieved.
     *
     * @throws Exception if fixture setup, HTTP I/O or bounded asynchronous waiting fails
     */
    @Test
    void handlesAncestor404EvenWhenDeletionEventCannotBeRead() throws Exception {
        BlockingQueue<LessConfigException> errors = new LinkedBlockingQueue<>();
        client.addStatusListener((status, error) -> {
            if (error != null) {
                errors.add(error);
            }
        });
        client.getConfig("payment", "prod", "application.yml");
        server.deletedEnvironment = true;
        assertEquals(404, errors.poll(5, TimeUnit.SECONDS).getStatusCode());
        assertNull(client.getConfig("payment", "prod", "application.yml"));
    }

    /**
     * Runs simultaneous first reads and verifies they share initialization and publish a single successful cached value.
     *
     * @throws Exception if fixture setup, HTTP I/O or bounded asynchronous waiting fails
     */
    @Test
    void concurrentFirstReadsShareOneInitializationAndOneFetch() throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(8);
        try {
            List<Future<Config>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                futures.add(callers.submit(() -> client.getConfig("payment", "prod", "application.yml")));
            }
            for (Future<Config> future : futures) {
                assertNotNull(future.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, server.initializations.get());
            assertEquals(1, server.reads.get());
        } finally {
            callers.shutdownNow();
        }
    }

    /**
     * Holds a server poll open and verifies close cancels it promptly instead of waiting for the long-poll timeout.
     *
     * @throws Exception if fixture setup, HTTP I/O or bounded asynchronous waiting fails
     */
    @Test
    void closeCancelsLongPollPromptlyAndTerminatesTheThread() throws Exception {
        client.close();
        java.util.Set<Thread> previousThreads = Thread.getAllStackTraces().keySet();
        server.pollWaitMillis = 60000;
        client = LessConfigClient.builder().endpoint(server.endpoint()).token("test-token").build();
        client.getConfig("payment", "prod", "application.yml");
        assertTrue(server.pollEntered.await(5, TimeUnit.SECONDS));
        java.util.Set<Thread> watcherThreads = new java.util.HashSet<>(Thread.getAllStackTraces().keySet());
        watcherThreads.removeAll(previousThreads);
        watcherThreads.removeIf(thread -> !thread.getName().startsWith("lessconfig-watch-"));
        assertFalse(watcherThreads.isEmpty());
        assertTimeoutPreemptively(Duration.ofSeconds(2), client::close);
        await(() -> watcherThreads.stream().noneMatch(Thread::isAlive));
        assertThrows(IllegalStateException.class, () -> client.getConfig("payment", "prod", "application.yml"));
    }

    /**
     * Checks reentrant callback reads and closure complete without holding internal cache locks or deadlocking.
     *
     * @throws Exception if fixture setup, HTTP I/O or bounded asynchronous waiting fails
     */
    @Test
    void listenerCanReadAnotherKeyAndCloseClient() throws Exception {
        CountDownLatch complete = new CountDownLatch(1);
        client.addListener(changes -> {
            client.getConfig("payment", "prod", "another");
            client.close();
            complete.countDown();
        });
        client.getConfig("payment", "prod", "application.yml");
        server.put("application.yml", "new", "yaml", "updated");
        assertTrue(complete.await(5, TimeUnit.SECONDS));
    }

    /**
     * Injects a backward success watermark and verifies it is rejected without advancing or corrupting the cache.
     *
     * @throws Exception if bounded asynchronous waiting fails
     */
    @Test
    void malformedWatchDoesNotAdvanceCheckpoint() throws Exception {
        BlockingQueue<LessConfigException> errors = new LinkedBlockingQueue<>();
        client.addStatusListener((status, error) -> {
            if (error != null) {
                errors.add(error);
            }
        });
        server.pollErrors.add(new TestServer.Response(200, "{\"revision\":0,\"changes\":[]}"));
        client.getConfig("payment", "prod", "application.yml");
        assertNotNull(errors.poll(5, TimeUnit.SECONDS));
        await(() -> server.requests.stream().filter(r -> Long.valueOf(1).equals(r.revision)).count() >= 2);
        assertFalse(server.requests.stream().anyMatch(r -> Long.valueOf(0).equals(r.revision)));
        server.put("application.yml", "after-malformed", "yaml", "updated");
        await(() -> "after-malformed".equals(client.getConfig("payment", "prod", "application.yml").getValue()));
    }

    /**
     * Deletes an old subscribed ancestor while its poll is held open, then reads an unrelated existing scope.
     * The old scope's 404 must not turn that new scope into a false null result.
     *
     * @throws Exception if fixture setup or bounded asynchronous waiting fails
     */
    @Test
    void deletedOldScopeDoesNotHideAnUnrelatedNewConfiguration() throws Exception {
        server.pollWaitMillis = 60000;
        client.getConfig("payment", "prod", "application.yml");
        assertTrue(server.pollEntered.await(5, TimeUnit.SECONDS));
        server.deletedEnvironment = true;
        Config shared = client.getConfig("shared", "prod", "key");
        assertNotNull(shared);
        assertEquals("shared-value", shared.getValue());
        assertEquals("shared-value", client.getConfig("shared", "prod", "key").getValue());
        assertNull(client.getConfig("payment", "prod", "application.yml"));
    }

}
