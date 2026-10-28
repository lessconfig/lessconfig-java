package com.lessconfig;

import org.jspecify.annotations.Nullable;

/**
 * Immutable server invalidation record; it contains no configuration values.
 */
public final class ConfigChange {
    /** Identifies a configuration resource in a change record. */
    public static final String CONFIG_CHANGE_RESOURCE_CONFIG = "config";
    /** Identifies an environment resource in a change record. */
    public static final String CONFIG_CHANGE_RESOURCE_ENVIRONMENT = "environment";
    /** Identifies a project resource in a change record. */
    public static final String CONFIG_CHANGE_RESOURCE_PROJECT = "project";

    /** Indicates that the resource was created. */
    public static final String CONFIG_CHANGE_TYPE_CREATED = "created";
    /** Indicates that the resource was deleted. */
    public static final String CONFIG_CHANGE_TYPE_DELETED = "deleted";
    /** Indicates that the resource was updated. */
    public static final String CONFIG_CHANGE_TYPE_UPDATED = "updated";
    /** Indicates that the resource was restored. */
    public static final String CONFIG_CHANGE_TYPE_RESTORED = "restored";

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
     * Server resource category, represented by a {@code CONFIG_CHANGE_RESOURCE_*} constant.
     */
    private final String resource;

    /**
     * Server operation, represented by a {@code CONFIG_CHANGE_TYPE_*} constant.
     */
    private final String type;

    /**
     * Creates a validated server record.
     *
     * @param revision    global event revision
     * @param project     project slug
     * @param environment environment slug, or null for project deletion
     * @param key         configuration key, or null for ancestor deletion
     * @param resource    server resource category, one of the {@code CONFIG_CHANGE_RESOURCE_*} constants
     * @param type        server operation, one of the {@code CONFIG_CHANGE_TYPE_*} constants
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
     * Returns the category of the changed resource.
     *
     * @return one of the {@code CONFIG_CHANGE_RESOURCE_*} constants
     * @see #CONFIG_CHANGE_RESOURCE_CONFIG
     * @see #CONFIG_CHANGE_RESOURCE_ENVIRONMENT
     * @see #CONFIG_CHANGE_RESOURCE_PROJECT
     */
    public String getResource() {
        return resource;
    }

    /**
     * Returns the operation applied to the resource.
     *
     * @return one of the {@code CONFIG_CHANGE_TYPE_*} constants
     * @see #CONFIG_CHANGE_TYPE_CREATED
     * @see #CONFIG_CHANGE_TYPE_DELETED
     * @see #CONFIG_CHANGE_TYPE_UPDATED
     * @see #CONFIG_CHANGE_TYPE_RESTORED
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
