package com.lessconfig;

import java.time.Duration;

/**
 * Configures a LessConfig client. Builders are not thread-safe; built clients are thread-safe.
 * All time values use {@link Duration}. Building a client performs no network requests.
 */
public class LessConfigClientBuilder {
    private String endpoint;
    private String token;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(10);
    private Duration watchTimeout = Duration.ofSeconds(60);
    private Duration watchReadTimeout = Duration.ofSeconds(90);
    private Duration retryInitialDelay = Duration.ofSeconds(1);
    private Duration retryMaxDelay = Duration.ofSeconds(30);

    /**
     * Creates an empty builder with the documented timeout defaults.
     */
    public LessConfigClientBuilder() {
    }

    /**
     * Creates a builder with the default settings.
     *
     * @return a new, independent builder
     */
    public static LessConfigClientBuilder builder() {
        return new LessConfigClientBuilder();
    }

    /**
     * Sets the complete HTTP(S) API base URL, including {@code /api/v1}.
     * Trailing slashes are removed; URL credentials, query strings and fragments are rejected at build time.
     *
     * @param endpoint required complete API base URL
     * @return this builder
     */
    public LessConfigClientBuilder endpoint(String endpoint) {
        this.endpoint = endpoint;
        return this;
    }

    /**
     * Sets the credential sent in the HTTP Bearer authorization header.
     *
     * @param token required nonempty API token without line breaks
     * @return this builder
     */
    public LessConfigClientBuilder token(String token) {
        this.token = token;
        return this;
    }

    /**
     * Sets the timeout for establishing an HTTP connection. Defaults to five seconds.
     *
     * @param timeout positive duration in whole milliseconds, at most {@link Integer#MAX_VALUE} milliseconds
     * @return this builder
     * @throws IllegalArgumentException if the duration is null, nonpositive, fractional in milliseconds or too large
     */
    public LessConfigClientBuilder connectTimeout(Duration timeout) {
        this.connectTimeout = validDuration(timeout);
        return this;
    }

    /**
     * Sets the socket read timeout for ordinary GET and watch initialization requests. Defaults to ten seconds.
     * This bounds an individual socket read, not the total duration of a multi-request initialization.
     *
     * @param timeout positive duration in whole milliseconds, at most {@link Integer#MAX_VALUE} milliseconds
     * @return this builder
     * @throws IllegalArgumentException if the duration cannot be represented as a positive Java socket timeout
     */
    public LessConfigClientBuilder readTimeout(Duration timeout) {
        this.readTimeout = validDuration(timeout);
        return this;
    }

    /**
     * Sets how long the server may hold one watch request. Defaults to sixty seconds.
     *
     * @param timeout whole-second duration between one and three hundred seconds, inclusive
     * @return this builder
     * @throws IllegalArgumentException if the duration is null, fractional in seconds or outside the supported range
     */
    public LessConfigClientBuilder watchTimeout(Duration timeout) {
        if (timeout == null || timeout.getNano() != 0 || timeout.getSeconds() < 1 || timeout.getSeconds() > 300) {
            throw new IllegalArgumentException("watchTimeout must be 1–300 whole seconds");
        }
        this.watchTimeout = timeout;
        return this;
    }

    /**
     * Sets the socket read timeout for a long poll. Defaults to ninety seconds.
     * It must exceed {@link #watchTimeout(Duration)} to allow the server to return its normal heartbeat.
     *
     * @param timeout positive duration in whole milliseconds, at most {@link Integer#MAX_VALUE} milliseconds
     * @return this builder
     * @throws IllegalArgumentException if the duration cannot be represented as a positive Java socket timeout
     */
    public LessConfigClientBuilder watchReadTimeout(Duration timeout) {
        this.watchReadTimeout = validDuration(timeout);
        return this;
    }

    /**
     * Sets the initial and maximum exponential retry delays. Defaults to one and thirty seconds.
     * Successful watch cycles reset the delay. Foreground reads are not retried automatically.
     *
     * @param initial positive initial delay in whole milliseconds
     * @param maximum positive maximum delay, at least the initial delay
     * @return this builder
     * @throws IllegalArgumentException if either duration is invalid or the maximum is smaller than the initial delay
     */
    public LessConfigClientBuilder retryDelay(Duration initial, Duration maximum) {
        validDuration(initial);
        validDuration(maximum);
        if (maximum.compareTo(initial) < 0) {
            throw new IllegalArgumentException("maximum retry delay must be >= initial delay");
        }
        this.retryInitialDelay = initial;
        this.retryMaxDelay = maximum;
        return this;
    }

    /**
     * Validates a duration before conversion at a Java socket/wait boundary.
     *
     * @param value required duration to validate
     * @return the same duration when valid
     * @throws IllegalArgumentException if the duration is null or not a positive, bounded whole-millisecond value
     */
    static Duration validDuration(Duration value) {
        if (value == null || value.isNegative() || value.isZero() || value.getNano() % 1_000_000 != 0
                || value.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("duration must be positive whole milliseconds, at most Integer.MAX_VALUE milliseconds");
        }
        return value;
    }

    /**
     * Validates the configuration and creates a client without opening connections or starting threads.
     *
     * @return a new client owned by the caller, which must be closed after use
     * @throws IllegalArgumentException if endpoint/token is missing or invalid, or the watch read timeout is too short
     */
    public DefaultLessConfigClient build() {
        if (watchReadTimeout.compareTo(watchTimeout) <= 0) {
            throw new IllegalArgumentException("watchReadTimeout must exceed watchTimeout");
        }
        return new DefaultLessConfigClient(endpoint, token, connectTimeout, readTimeout,
                watchTimeout, watchReadTimeout, retryInitialDelay, retryMaxDelay);
    }
}
