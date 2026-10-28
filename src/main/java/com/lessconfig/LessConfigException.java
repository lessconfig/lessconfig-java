package com.lessconfig;

import org.jspecify.annotations.Nullable;

/**
 * A transport, protocol or server error.
 */
public class LessConfigException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    /**
     * Stable server business code, or null for a transport/non-JSON failure. @serial
     */
    private final @Nullable String code;
    /**
     * HTTP error status, or zero when unavailable. @serial
     */
    private final int statusCode;


    /**
     * Creates a server error without an HTTP status.
     *
     * @param code    stable server business code
     * @param message human-readable failure explanation
     */
    public LessConfigException(String code, String message) {
        this(0, code, message);
    }

    /**
     * Creates a server error with its HTTP status and optional stable business code.
     *
     * @param statusCode HTTP status, or zero if unavailable
     * @param code       stable server business code, or null for a non-JSON error response
     * @param message    human-readable failure explanation
     */
    public LessConfigException(int statusCode, @Nullable String code, String message) {
        super(message);
        this.statusCode = statusCode;
        this.code = code;
    }

    /**
     * Creates a transport or response-decoding error.
     *
     * @param message human-readable failure explanation
     * @param cause   underlying failure, or null when unavailable
     */
    public LessConfigException(String message, @Nullable Throwable cause) {
        super(message, cause);
        this.code = null;
        this.statusCode = 0;
    }

    /**
     * Returns the server business code.
     *
     * @return business code, or null for transport/non-JSON errors
     */
    @Nullable
    public String getCode() {
        return code;
    }

    /**
     * Returns the HTTP status associated with the failure.
     *
     * @return status code, or zero when no server error response was received
     */
    public int getStatusCode() {
        return statusCode;
    }
}
