package com.lessconfig;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * Internal, cancellable GET transport using only the Java 8 networking API.
 */
final class HttpTransport implements AutoCloseable {
    private final String endpoint;
    private final String token;
    private final Duration connectTimeout;
    private final Set<Request> connections = new HashSet<>();
    private boolean closed;

    /**
     * Creates a transport without opening a connection.
     *
     * @param endpoint       HTTP(S) API root URL without a version
     * @param token          nonempty Bearer credential
     * @param connectTimeout positive connection timeout
     * @throws IllegalArgumentException if endpoint or token is invalid
     */
    HttpTransport(String endpoint, String token, Duration connectTimeout) {
        this.endpoint = normalizeEndpoint(endpoint);
        if (token == null || token.trim().isEmpty() || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("token must be nonempty and contain no line breaks");
        }
        this.token = token;
        this.connectTimeout = connectTimeout;
    }

    /**
     * Validates the endpoint and removes trailing slashes.
     *
     * @param endpoint API root URL without a version
     * @return normalized ASCII URL
     * @throws IllegalArgumentException if the URL is missing, malformed or contains credentials/query/fragment
     */
    static String normalizeEndpoint(String endpoint) {
        if (endpoint == null) {
            throw new IllegalArgumentException("endpoint cannot be null");
        }
        try {
            URI uri = new URI(endpoint);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("endpoint must be an HTTP(S) API base URL without credentials, query or fragment");
            }
            String result = uri.toASCIIString();
            while (result.endsWith("/")) {
                result = result.substring(0, result.length() - 1);
            }
            return result;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid endpoint", e);
        }
    }

    /**
     * Performs one authenticated GET and closes its stream/connection on every exit path.
     *
     * @param path        validated path relative to the API root, including its version and any encoded query
     * @param readTimeout positive socket read timeout
     * @return decoded JSON response
     * @throws LessConfigException on HTTP, network, protocol or cancellation failure
     */
    JSONObject get(String path, Duration readTimeout) {
        HttpURLConnection connection = null;
        Request request = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint + path).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout((int) connectTimeout.toMillis());
            connection.setReadTimeout((int) readTimeout.toMillis());
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Accept", "application/json");
            synchronized (connections) {
                if (closed) {
                    throw new IllegalStateException("Client is closed");
                }
                request = new Request(connection);
                connections.add(request);
            }
            int status = connection.getResponseCode();
            String body = read(status == 200 ? connection.getInputStream() : connection.getErrorStream());
            if (request.cancelled) {
                throw new CancelledException(null);
            }
            if (status != 200) {
                String code = null;
                String message = "HTTP request failed: " + status;
                try {
                    JSONObject error = new JSONObject(body);
                    code = error.optString("code", null);
                    message = error.optString("message", message);
                } catch (JSONException ignored) {
                    // A proxy may return HTML or no body; retain its HTTP status.
                }
                throw new LessConfigException(status, code, message);
            }
            try {
                return new JSONObject(body);
            } catch (JSONException e) {
                throw new LessConfigException("Invalid JSON response", e);
            }
        } catch (LessConfigException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // Java 8 can throw RuntimeException/NPE when disconnect races with connection setup.
            // Treat intentional cancellation separately from server/network failures.
            if (request != null && request.cancelled) {
                throw new CancelledException(e);
            }
            throw new LessConfigException("HTTP request failed", e);
        } finally {
            if (connection != null) {
                synchronized (connections) {
                    connections.remove(request);
                }
                connection.disconnect();
            }
        }
    }

    /**
     * Consumes and closes a response stream as UTF-8.
     *
     * @param input response stream, or null when the HTTP response has no error body
     * @return response text, empty for an absent stream
     * @throws IOException if reading fails
     */
    private static String read(InputStream input) throws IOException {
        if (input == null) {
            return "";
        }
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Cancel current requests; subsequent requests remain possible.
     */
    void cancel() {
        Request[] active;
        synchronized (connections) {
            active = connections.toArray(new Request[0]);
            for (Request request : active) {
                request.cancelled = true;
            }
        }
        for (Request request : active) {
            request.connection.disconnect();
        }
    }

    /**
     * Prevents new requests and cancels outstanding connections.
     */
    @Override
    public void close() {
        synchronized (connections) {
            closed = true;
        }
        cancel();
    }

    private static final class Request {
        final HttpURLConnection connection;
        volatile boolean cancelled;

        Request(HttpURLConnection connection) {
            this.connection = connection;
        }
    }

    static final class CancelledException extends LessConfigException {
        private static final long serialVersionUID = 1L;

        CancelledException(Throwable cause) {
            super("HTTP request cancelled", cause);
        }
    }
}
