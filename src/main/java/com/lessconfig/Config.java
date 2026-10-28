package com.lessconfig;

import java.util.Objects;

/**
 * Configuration content and metadata for one key within an environment.
 * Client-returned instances are detached copies; changing an instance does not change the client's cache.
 */
public class Config {
    /**
     * Case-sensitive key identifying the configuration within its environment.
     */
    private String key;

    /**
     * Original configuration text, preserving whitespace, line breaks and comments.
     */
    private String value;

    /**
     * Format recognized by the SDK; UNKNOWN represents an unrecognized server type.
     */
    private ConfigType type;

    /**
     * Original server type name, retained even when {@link #type} is UNKNOWN.
     */
    private String rawType;

    /**
     * Human-readable configuration description supplied by the server.
     */
    private String description;

    /**
     * Revision of this configuration resource, independent of the global watch progress.
     */
    private Long revision;

    /**
     * Creates a configuration object whose properties can be populated through its setters.
     */
    public Config() {
    }

    /**
     * Returns the case-sensitive configuration key.
     *
     * @return case-sensitive configuration key
     */
    public String getKey() {
        return key;
    }

    /**
     * Sets the case-sensitive configuration key on this detached bean.
     *
     * @param key case-sensitive configuration key; never null
     * @throws NullPointerException if the value is null
     */
    public void setKey(String key) {
        this.key = Objects.requireNonNull(key, "key");
    }

    /**
     * Returns the raw configuration text, including whitespace and comments.
     *
     * @return raw configuration text, including whitespace and comments
     */
    public String getValue() {
        return value;
    }

    /**
     * Sets the raw configuration text without parsing or normalizing its contents.
     *
     * @param value raw configuration text, including whitespace and comments; never null
     * @throws NullPointerException if the value is null
     */
    public void setValue(String value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    /**
     * Returns the recognized configuration format.
     * Inspect {@link #getRawType()} when the server format maps to {@link ConfigType#UNKNOWN}.
     *
     * @return configuration format recognized by the SDK
     */
    public ConfigType getType() {
        return type;
    }

    /**
     * Sets the recognized configuration format for this object.
     *
     * @param type recognized configuration type, or UNKNOWN; never null
     * @throws NullPointerException if the value is null
     */
    public void setType(ConfigType type) {
        this.type = Objects.requireNonNull(type, "type");
    }

    /**
     * Returns the server type name, including unrecognized future formats.
     *
     * @return server type name, including unrecognized future formats
     */
    public String getRawType() {
        return rawType;
    }

    /**
     * Sets the original server type name, including formats unknown to this SDK version.
     *
     * @param rawType server type name, including unrecognized future formats; never null
     * @throws NullPointerException if the value is null
     */
    public void setRawType(String rawType) {
        this.rawType = Objects.requireNonNull(rawType, "rawType");
    }

    /**
     * Returns the configuration description.
     *
     * @return configuration description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets the configuration description on this detached bean.
     *
     * @param description configuration description; never null
     * @throws NullPointerException if the value is null
     */
    public void setDescription(String description) {
        this.description = Objects.requireNonNull(description, "description");
    }

    /**
     * Returns the revision of this configuration resource.
     * The client tracks global watch progress separately from configuration revisions.
     *
     * @return configuration resource revision
     */
    public Long getRevision() {
        return revision;
    }

    /**
     * Sets the revision associated with this configuration object.
     *
     * @param revision configuration resource revision; never null
     * @throws NullPointerException if the value is null
     */
    public void setRevision(Long revision) {
        this.revision = Objects.requireNonNull(revision, "revision");
    }
}
