package com.lessconfig;

/**
 * Observable health and lifecycle of a client's configuration watcher.
 */
public enum WatchStatus {
    /**
     * No configuration scope is currently subscribed.
     */
    IDLE,
    /**
     * The cache has been synchronized and the watcher is polling normally.
     */
    WATCHING,
    /**
     * The watcher is retrying or reinitializing; previously cached values remain readable.
     */
    RETRYING,
    /**
     * A permanent error stopped the watcher. Create a new client after correcting its configuration.
     */
    STOPPED,
    /**
     * The client was closed and cannot be reused.
     */
    CLOSED
}
