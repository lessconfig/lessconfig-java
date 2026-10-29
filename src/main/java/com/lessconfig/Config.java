package com.lessconfig;

import java.util.Objects;

/**
 * Configuration content and metadata for one key within an environment.
 * Client-returned instances are detached copies; changing an instance does not change the client's cache.
 */
public class Config {
    /**
     * Configuration format for plain text.
     */
    public static final String CONFIG_TYPE_TEXT = "text";
    /**
     * Configuration format for a boolean value.
     */
    public static final String CONFIG_TYPE_BOOLEAN = "boolean";
    /**
     * Configuration format for a numeric value.
     */
    public static final String CONFIG_TYPE_NUMBER = "number";
    /**
     * Configuration format for JSON content.
     */
    public static final String CONFIG_TYPE_JSON = "json";
    /**
     * Configuration format for YAML content.
     */
    public static final String CONFIG_TYPE_YAML = "yaml";
    /**
     * Configuration format for Java properties content.
     */
    public static final String CONFIG_TYPE_PROPERTIES = "properties";

    /**
     * Case-sensitive key identifying the configuration within its environment.
     */
    private String key;

    /**
     * Original configuration text, preserving whitespace, line breaks and comments.
     */
    private String value;

    /**
     * Configuration format exactly as supplied by the server, including unfamiliar type names.
     */
    private String type;

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
     * Returns the configuration format exactly as supplied by the server.
     * Known formats have corresponding {@code CONFIG_TYPE_*} constants; unfamiliar names are preserved.
     *
     * @return original configuration type name
     * @see #CONFIG_TYPE_TEXT
     * @see #CONFIG_TYPE_BOOLEAN
     * @see #CONFIG_TYPE_NUMBER
     * @see #CONFIG_TYPE_JSON
     * @see #CONFIG_TYPE_YAML
     * @see #CONFIG_TYPE_PROPERTIES
     */
    public String getType() {
        return type;
    }

    /**
     * Sets the configuration format without changing its spelling or validating it against known formats.
     *
     * @param type configuration type name; never null
     * @throws NullPointerException if the value is null
     */
    public void setType(String type) {
        this.type = Objects.requireNonNull(type, "type");
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
