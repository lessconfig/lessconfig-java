package com.lessconfig;

import org.jspecify.annotations.Nullable;

/**
 * Observes watcher health independently of configuration changes.
 * Notifications are serialized per client and run outside cache locks on the thread causing
 * the transition (the caller for startup/close, normally the watcher for retries/recovery).
 * Callbacks must return promptly. Runtime exceptions are logged and isolated.
 */
@FunctionalInterface
public interface StatusListener {
    /**
     * Receives a lifecycle transition or a new background failure while retrying.
     * Registration does not replay the current status; use {@link LessConfigClient#getStatus()} to inspect it.
     * Cached values remain readable in RETRYING and STOPPED states.
     *
     * @param status new watcher status; never null
     * @param error  failure for RETRYING/STOPPED, or null for normal lifecycle transitions
     */
    void onStatusChange(WatchStatus status, @Nullable LessConfigException error);
}
