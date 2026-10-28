package com.lessconfig;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral contract tests for client configuration and lifecycle.
 */
class LessConfigClientBuilderTest {
    /**
     * Verifies invalid endpoint, credential and timeout combinations are rejected before a client is built.
     */
    @Test
    void validatesConnectionSettings() {
        for (String endpoint : new String[]{null, "", "file:///tmp/test", "http://", "http://u:p@localhost", "http://localhost?x=1", "http://localhost/#x"}) {
            assertThrows(IllegalArgumentException.class, () -> LessConfigClient.builder().endpoint(endpoint).token("token").build());
        }
        for (String token : new String[]{null, "", "  ", "value\r\nHeader: value"}) {
            assertThrows(IllegalArgumentException.class, () -> LessConfigClient.builder().endpoint("http://localhost").token(token).build());
        }
        assertThrows(IllegalArgumentException.class, () -> LessConfigClient.builder().readTimeout(Duration.ofMillis(0)));
        assertThrows(IllegalArgumentException.class, () -> LessConfigClient.builder().connectTimeout(Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class, () -> LessConfigClient.builder().watchTimeout(Duration.ofSeconds(301)));
        assertThrows(IllegalArgumentException.class, () -> LessConfigClient.builder().retryDelay(Duration.ofMillis(100), Duration.ofMillis(50)));
        assertThrows(IllegalArgumentException.class, () -> LessConfigClient.builder().endpoint("http://localhost")
                .token("t").watchTimeout(Duration.ofSeconds(90)).build());
    }

    /**
     * Checks explicit startup is optional/idempotent and a closed client rejects later operational calls.
     */
    @Test
    void buildIsLazyAndCloseIsIdempotent() {
        LessConfigClient client = LessConfigClientBuilder.builder().endpoint("http://localhost").token("test").build();
        client.start();
        client.start();
        client.close();
        client.close();
        assertThrows(IllegalStateException.class, client::start);
        assertThrows(IllegalStateException.class, client::getProjects);
        assertThrows(IllegalStateException.class, () -> client.getConfig("p", "e", "k"));
        assertThrows(IllegalStateException.class, () -> client.addListener(changes -> {
        }));
    }

    /**
     * Checks path-injection and unsupported identifier inputs fail before any HTTP request is emitted.
     *
     * @throws Exception if fixture setup, HTTP I/O or bounded asynchronous waiting fails
     */
    @Test
    void rejectsInvalidIdentifiersBeforeSendingRequests() throws Exception {
        try (TestServer server = new TestServer(); LessConfigClient client = server.client()) {
            for (String key : new String[]{"", ".", "..", "../key", "a/b", "a?x", "中文", "line\n"}) {
                assertThrows(IllegalArgumentException.class, () -> client.getConfig("payment", "prod", key));
            }
            assertTrue(server.requests.isEmpty());
            assertThrows(NullPointerException.class, () -> client.addListener((ConfigListener) null));
        }
    }

    /**
     * Verifies endpoint normalization preserves valid, case-sensitive configuration identifiers.
     *
     * @throws Exception if fixture setup, HTTP I/O or bounded asynchronous waiting fails
     */
    @Test
    void acceptsTrailingSlashesAndCaseSensitiveIdentifiers() throws Exception {
        try (TestServer server = new TestServer(); LessConfigClient client = LessConfigClient.builder()
                .endpoint(server.endpoint() + "///").token("test-token").build()) {
            server.put("_Key.A-1", "v", "text", "created");
            assertEquals("v", client.getConfig("payment", "prod", "_Key.A-1").getValue());
        }
    }

    /**
     * Verifies Duration boundaries reject truncation/overflow while accepting representable socket and watch values.
     */
    @Test
    void validatesDurationPrecisionAndRange() {
        for (Duration duration : new Duration[]{null, Duration.ZERO, Duration.ofMillis(-1), Duration.ofNanos(1),
                Duration.ofMillis((long) Integer.MAX_VALUE + 1), Duration.ofSeconds(Long.MAX_VALUE)}) {
            assertThrows(IllegalArgumentException.class, () -> LessConfigClient.builder().connectTimeout(duration));
            assertThrows(IllegalArgumentException.class, () -> LessConfigClient.builder().readTimeout(duration));
            assertThrows(IllegalArgumentException.class, () -> LessConfigClient.builder().watchReadTimeout(duration));
            assertThrows(IllegalArgumentException.class, () -> LessConfigClient.builder().retryDelay(duration, Duration.ofSeconds(1)));
        }
        for (Duration duration : new Duration[]{null, Duration.ZERO, Duration.ofMillis(1500), Duration.ofSeconds(301)}) {
            assertThrows(IllegalArgumentException.class, () -> LessConfigClient.builder().watchTimeout(duration));
        }
        try (LessConfigClient ignored = LessConfigClient.builder().endpoint("http://localhost").token("test")
                .connectTimeout(Duration.ofMillis(1)).readTimeout(Duration.ofMillis(Integer.MAX_VALUE))
                .watchTimeout(Duration.ofSeconds(300)).watchReadTimeout(Duration.ofSeconds(301))
                .retryDelay(Duration.ofMillis(1), Duration.ofMillis(1)).build()) {
            assertEquals(WatchStatus.IDLE, ignored.getStatus());
        }
    }

}
