package com.lessconfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.stream.Stream;

import static com.lessconfig.Config.CONFIG_TYPE_BOOLEAN;
import static com.lessconfig.Config.CONFIG_TYPE_JSON;
import static com.lessconfig.Config.CONFIG_TYPE_NUMBER;
import static com.lessconfig.Config.CONFIG_TYPE_PROPERTIES;
import static com.lessconfig.Config.CONFIG_TYPE_TEXT;
import static com.lessconfig.Config.CONFIG_TYPE_YAML;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

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
            assertEquals(CONFIG_TYPE_YAML, config.getType());
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
            client.getConfig("payment", "prod", "application.yml").setType(CONFIG_TYPE_TEXT);
            assertEquals(CONFIG_TYPE_YAML, client.getConfig("payment", "prod", "application.yml").getType());
            client.getConfigs("payment", "prod").get(0).setKey("corrupted");
            assertEquals("application.yml", client.getConfigs("payment", "prod").get(0).getKey());
            assertEquals(CONFIG_TYPE_YAML, client.getConfigs("payment", "prod").get(0).getType());
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
     * Verifies actual server type names can be compared with public constants after decoding and caching.
     *
     * @param serverType literal type name sent by the server fixture
     * @param expectedType public constant applications use to identify the format
     */
    @ParameterizedTest
    @MethodSource("configurationTypes")
    void readsConfigurationTypesAsStringConstants(String serverType, String expectedType) {
        server.put("typed", "raw", serverType, "created");
        try (LessConfigClient client = server.client()) {
            assertEquals(expectedType, client.getConfig("payment", "prod", "typed").getType());
            assertEquals(expectedType, client.getConfig("payment", "prod", "typed").getType());
        }
    }

    /**
     * Pairs independent protocol literals with the corresponding SDK constants.
     *
     * @return the six supported configuration formats and their public constants
     */
    private static Stream<Arguments> configurationTypes() {
        return Stream.of(
                arguments("text", CONFIG_TYPE_TEXT),
                arguments("boolean", CONFIG_TYPE_BOOLEAN),
                arguments("number", CONFIG_TYPE_NUMBER),
                arguments("json", CONFIG_TYPE_JSON),
                arguments("yaml", CONFIG_TYPE_YAML),
                arguments("properties", CONFIG_TYPE_PROPERTIES));
    }

    /**
     * Checks forward compatibility: unfamiliar type names retain their original spelling and case.
     *
     * @param serverType unfamiliar format name supplied by the server
     */
    @ParameterizedTest
    @ValueSource(strings = {"toml", "CustomFormat"})
    void preservesUnknownTypeNames(String serverType) {
        server.put("new-format", "raw", serverType, "created");
        try (LessConfigClient client = server.client()) {
            Config config = client.getConfig("payment", "prod", "new-format");
            assertEquals(serverType, config.getType());
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
