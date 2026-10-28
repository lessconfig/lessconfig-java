package com.lessconfig;

import org.jspecify.annotations.Nullable;

/**
 * Immutable server invalidation record; it contains no configuration values.
 */
public final class ConfigChange {
    /**
     * Global revision assigned to this event; the watch response can have a later watermark.
     */
    private final long revision;

    /**
     * Case-sensitive slug of the project containing the changed resource.
     */
    private final String project;

    /**
     * Environment slug, or null when the changed resource is a project.
     */
    private final String environment;

    /**
     * Configuration key, or null when the changed resource is an environment or project.
     */
    private final String key;

    /**
     * Server resource category: {@code config}, {@code environment} or {@code project}.
     */
    private final String resource;

    /**
     * Server operation: {@code created}, {@code updated}, {@code deleted} or {@code restored}.
     */
    private final String type;

    /**
     * Creates a validated server record.
     *
     * @param revision    global event revision
     * @param project     project slug
     * @param environment environment slug, or null for project deletion
     * @param key         configuration key, or null for ancestor deletion
     * @param resource    server resource name
     * @param type        server operation name
     */
    ConfigChange(long revision, String project, @Nullable String environment, @Nullable String key,
                 String resource, String type) {
        this.revision = revision;
        this.project = project;
        this.environment = environment;
        this.key = key;
        this.resource = resource;
        this.type = type;
    }

    /**
     * Returns this event's global revision, not the response watermark.
     *
     * @return this event's global revision, not the response watermark
     */
    public long getRevision() {
        return revision;
    }

    /**
     * Returns the project slug; never null.
     *
     * @return project slug; never null
     */
    public String getProject() {
        return project;
    }

    /**
     * Returns the environment slug, or null for a project deletion.
     *
     * @return environment slug, or null for a project deletion
     */
    public @Nullable String getEnvironment() {
        return environment;
    }

    /**
     * Returns the configuration key, or null for project/environment deletion.
     *
     * @return configuration key, or null for project/environment deletion
     */
    public @Nullable String getKey() {
        return key;
    }

    /**
     * Returns the server resource name: config, environment or project.
     *
     * @return server resource name: config, environment or project
     */
    public String getResource() {
        return resource;
    }

    /**
     * Returns the server operation name: created, updated, deleted or restored.
     *
     * @return server operation name: created, updated, deleted or restored
     */
    public String getType() {
        return type;
    }

    /**
     * Checks whether this invalidation intersects a subscription, including ancestor deletions.
     *
     * @param scope key or environment selection
     * @return true if the scope must be refreshed
     */
    boolean affects(ConfigId scope) {
        return project.equals(scope.getProject())
                && (environment == null || environment.equals(scope.getEnvironment()))
                && (key == null || scope.getKey() == null || key.equals(scope.getKey()));
    }
}
