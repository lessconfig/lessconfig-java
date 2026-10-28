package com.lessconfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral contract tests for the HTTP read API and cache boundaries.
 */
class LessConfigApiTest {
    private TestServer server;

    /**
     * Starts an isolated local HTTP server for each protocol test.
     *
     * @throws Exception if fixture setup, HTTP I/O or bounded asynchronous waiting fails
     */
    @BeforeEach
    void setup() throws Exception {
        server = new TestServer();
    }

    /**
     * Stops the fixture and releases its sockets and executor.
     */
    @AfterEach
    void teardown() {
        server.close();
    }

    /**
     * Verifies initialization precedes reads, UTF-8 is preserved, metadata is decoded, and repeated config reads hit cache.
     */
    @Test
    void readsUtf8ConfigurationsAndFreshMetadata() {
        try (LessConfigClient client = server.client()) {
            Config config = client.getConfig("payment", "prod", "application.yml");
            assertNotNull(config);
            assertEquals("# 原文\nport: 8080\n", config.getValue());
            assertEquals(ConfigType.YAML, config.getType());
            assertEquals("yaml", config.getRawType());
            assertEquals(1L, config.getRevision());
            assertEquals("支付", client.getProject("payment").getName());
            assertEquals("p1", client.getEnvironment("payment", "prod").getProjectId());
            assertEquals(1, client.getProjects().size());
            assertEquals(1, client.getEnvironments("payment").size());
            client.getConfig("payment", "prod", "application.yml");
            assertEquals(1, server.reads.get());
            assertEquals("/api/v1/watch", server.requests.get(0).path);
            assertNull(server.requests.get(0).revision);
        }
    }

    /**
     * Ensures mutable DTOs returned to callers cannot alter shared cached values and collection structure is immutable.
     */
    @Test
    void keepsCachedBeansIsolatedFromCallerMutation() {
        try (LessConfigClient client = server.client()) {
            client.getConfig("payment", "prod", "application.yml").setValue("corrupted");
            assertTrue(client.getConfig("payment", "prod", "application.yml").getValue().startsWith("# 原文"));
            client.getConfigs("payment", "prod").get(0).setKey("corrupted");
            assertEquals("application.yml", client.getConfigs("payment", "prod").get(0).getKey());
            assertThrows(UnsupportedOperationException.class, () -> client.getConfigs("payment", "prod").clear());
        }
    }

    /**
     * Checks that recognized resource absence is nullable while collection/authorization failures remain typed SDK errors.
     */
    @Test
    void distinguishesMissingResourcesFromErrors() {
        try (LessConfigClient client = server.client()) {
            assertNull(client.getConfig("payment", "prod", "missing"));
            assertNull(client.getProject("missing"));
            assertNull(client.getEnvironment("payment", "missing"));
            assertNull(client.getConfig("missing", "prod", "application.yml"));
            assertThrows(LessConfigException.class, () -> client.getEnvironments("missing"));
        }
        try (LessConfigClient client = LessConfigClient.builder().endpoint(server.endpoint()).token("wrong").build()) {
            LessConfigException error = assertThrows(LessConfigException.class, client::getProjects);
            assertEquals("unauthorized", error.getCode());
            assertEquals(401, error.getStatusCode());
        }
    }

    /**
     * Checks forward compatibility: a future server format retains its raw name and maps to UNKNOWN.
     */
    @Test
    void preservesUnknownConfigTypesAsRawText() {
        server.put("new-format", "raw", "toml", "created");
        try (LessConfigClient client = server.client()) {
            Config config = client.getConfig("payment", "prod", "new-format");
            assertEquals(ConfigType.UNKNOWN, config.getType());
            assertEquals("toml", config.getRawType());
        }
    }

    /**
     * Verifies proxy failures retain their HTTP status even when the response body is absent or not JSON.
     */
    @Test
    void handlesEmptyHtmlAndMalformedErrorResponses() {
        try (LessConfigClient client = server.client()) {
            for (String body : new String[]{"", "<html>unavailable</html>", "{invalid"}) {
                server.fixedResponse = new TestServer.Response(502, body);
                LessConfigException error = assertThrows(LessConfigException.class, client::getProjects);
                assertEquals(502, error.getStatusCode());
                assertNull(error.getCode());
            }
        }
    }

    /**
     * Ensures redirects fail without issuing a second request carrying the Bearer credential.
     */
    @Test
    void rejectsRedirectsWithoutForwardingCredentials() {
        server.fixedResponse = new TestServer.Response(302, "");
        try (LessConfigClient client = server.client()) {
            assertEquals(302, assertThrows(LessConfigException.class, client::getProjects).getStatusCode());
            assertEquals(1, server.requests.size());
        }
    }

    /**
     * Verifies malformed successful responses become SDK protocol errors with the underlying decoding cause.
     */
    @Test
    void wrapsMalformedSuccessResponses() {
        try (LessConfigClient client = server.client()) {
            for (String body : new String[]{"not JSON", "{}", "{\"items\":[{}]}"}) {
                server.fixedResponse = new TestServer.Response(200, body);
                assertNotNull(assertThrows(LessConfigException.class, client::getProjects).getCause());
            }
        }
    }

    /**
     * Verifies empty and explicitly null collection payloads return immutable empty lists, never null.
     */
    @Test
    void emptyCollectionsAreAlwaysNonNull() {
        try (LessConfigClient client = server.client()) {
            for (String items : new String[]{"[]", "null"}) {
                server.fixedResponse = new TestServer.Response(200, "{\"items\":" + items + ",\"total\":0}");
                assertNotNull(client.getProjects());
                assertTrue(client.getProjects().isEmpty());
                assertNotNull(client.getEnvironments("payment"));
                assertTrue(client.getEnvironments("payment").isEmpty());
                assertThrows(UnsupportedOperationException.class, () -> client.getProjects().add(new Project()));
            }
        }
    }

    /**
     * Holds a real socket response to verify the Duration read timeout is applied at the transport boundary.
     *
     * @throws Exception if the local fixture cannot be created
     */
    @Test
    void socketReadTimeoutUsesConfiguredDuration() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        server.beforeRead = () -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        try (LessConfigClient shortTimeoutClient = LessConfigClient.builder().endpoint(server.endpoint())
                .token("test-token").readTimeout(Duration.ofMillis(30)).build()) {
            LessConfigException error = assertThrows(LessConfigException.class,
                    () -> shortTimeoutClient.getConfig("payment", "prod", "application.yml"));
            assertTrue(error.getCause() instanceof java.net.SocketTimeoutException);
            assertEquals(0, error.getStatusCode());
        } finally {
            release.countDown();
        }
    }

}
