package com.lessconfig;

import java.util.List;

/**
 * Receives configuration invalidations after the affected cache has been refreshed successfully.
 * Callbacks run serially on the client's daemon watcher thread, outside cache locks.
 * Keep callbacks short: a slow callback delays the next watch. A callback may read the client or close it.
 * Runtime exceptions are logged and isolated from other listeners.
 * Watch health is reported separately through {@link StatusListener}.
 */
@FunctionalInterface
public interface ConfigListener {
    /**
     * Receives a nonempty, unmodifiable batch of server records in revision order.
     * Records contain no old/new values; use {@link LessConfigClient#getConfig(String, String, String)}
     * to read the refreshed cache. Records can be coalesced or repeated; this is not an audit-log API.
     * No event is synthesized for an initial load or a history-expiration resynchronization.
     *
     * @param changes server-provided invalidations matching this listener's scope; never null
     */
    void onChange(List<ConfigChange> changes);
}
