package com.lessconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.lessconfig.TestServer.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Contract tests for health notifications independent of configuration-change delivery.
 */
class StatusListenerTest {
    /**
     * Verifies normal startup, transient failure, successful recovery and closure are observable in order.
     * HTTP retry errors must not be delivered through the configuration listener.
     *
     * @throws Exception if fixture setup or bounded asynchronous waiting fails
     */
    @Test
    void reportsRetryAndRecoveryWithoutConfigCallbacks() throws Exception {
        try (TestServer server = new TestServer(); LessConfigClient client = server.client()) {
            BlockingQueue<WatchStatus> statuses = new LinkedBlockingQueue<>();
            BlockingQueue<LessConfigException> errors = new LinkedBlockingQueue<>();
            AtomicInteger configCallbacks = new AtomicInteger();
            client.addListener(changes -> configCallbacks.incrementAndGet());
            client.addStatusListener((status, error) -> {
                statuses.add(status);
                if (error != null) {
                    errors.add(error);
                }
            });
            assertEquals(WatchStatus.IDLE, client.getStatus());
            server.pollErrors.add(TestServer.error(503, "unavailable"));
            client.getConfig("payment", "prod", "application.yml");
            assertEquals(WatchStatus.WATCHING, statuses.poll(5, TimeUnit.SECONDS));
            assertEquals(WatchStatus.RETRYING, statuses.poll(5, TimeUnit.SECONDS));
            assertEquals(503, errors.poll(5, TimeUnit.SECONDS).getStatusCode());
            assertEquals(WatchStatus.WATCHING, statuses.poll(5, TimeUnit.SECONDS));
            assertEquals(0, configCallbacks.get());
            assertNull(client.getWatchError());
            client.close();
            assertEquals(WatchStatus.CLOSED, statuses.poll(5, TimeUnit.SECONDS));
            assertEquals(WatchStatus.CLOSED, client.getStatus());
        }
    }

    /**
     * Verifies permanent authentication failure reports STOPPED and preserves the original server business code.
     *
     * @throws Exception if fixture setup or bounded asynchronous waiting fails
     */
    @Test
    void reportsPermanentAuthenticationFailure() throws Exception {
        try (TestServer server = new TestServer(); LessConfigClient client = server.client()) {
            BlockingQueue<LessConfigException> failures = new LinkedBlockingQueue<>();
            client.addStatusListener((status, error) -> {
                if (status == WatchStatus.STOPPED) {
                    failures.add(error);
                }
            });
            server.pollErrors.add(TestServer.error(401, "invalid_api_token"));
            client.getConfig("payment", "prod", "application.yml");
            assertEquals("invalid_api_token", failures.poll(5, TimeUnit.SECONDS).getCode());
            assertEquals(WatchStatus.STOPPED, client.getStatus());
            assertNotNull(client.getConfig("payment", "prod", "application.yml"));
        }
    }

    /**
     * Checks duplicate/removal semantics and isolates a throwing health observer from other observers.
     * Expected exception logs are captured and asserted instead of being printed as apparent build failures.
     *
     * @throws Exception if fixture setup fails
     */
    @Test
    @ResourceLock("com.lessconfig.DefaultLessConfigClient.logger")
    void isolatesFailuresAndRemovesStatusObservers() throws Exception {
        IllegalStateException failure = new IllegalStateException("test observer failure");
        try (ExpectedListenerLog logs = new ExpectedListenerLog(failure);
             TestServer server = new TestServer(); LessConfigClient client = server.client()) {
            List<WatchStatus> received = new CopyOnWriteArrayList<>();
            AtomicInteger removedCalls = new AtomicInteger();
            StatusListener removed = (status, error) -> removedCalls.incrementAndGet();
            StatusListener observer = (status, error) -> received.add(status);
            client.addStatusListener((status, error) -> {
                throw failure;
            });
            client.addStatusListener(observer);
            client.addStatusListener(observer);
            client.addStatusListener(removed);
            client.removeStatusListener(removed);
            client.getConfig("payment", "prod", "application.yml");
            client.close();
            assertEquals(2, received.size());
            assertEquals(WatchStatus.WATCHING, received.get(0));
            assertEquals(WatchStatus.CLOSED, received.get(1));
            assertEquals(0, removedCalls.get());
            logs.assertLogged("LessConfig status listener failed");
            logs.assertLogged("LessConfig status listener failed");
        }
    }

    /**
     * Verifies a status callback can close the client reentrantly and still receive the queued CLOSED transition.
     *
     * @throws Exception if fixture setup or bounded asynchronous waiting fails
     */
    @Test
    void permitsReentrantCloseWithoutStartingAnOrphanWatcher() throws Exception {
        try (TestServer server = new TestServer(); LessConfigClient client = server.client()) {
            List<WatchStatus> received = new CopyOnWriteArrayList<>();
            client.addStatusListener((status, error) -> {
                received.add(status);
                if (status == WatchStatus.WATCHING) {
                    client.close();
                }
            });
            client.getConfig("payment", "prod", "application.yml");
            await(() -> received.contains(WatchStatus.CLOSED));
            assertEquals(WatchStatus.CLOSED, client.getStatus());
            assertFalse(server.requests.stream().anyMatch(r -> r.revision != null));
        }
    }
}
