package com.lessconfig;

/**
 * Known configuration formats. Values are returned as raw strings; the SDK does not parse them.
 */
public enum ConfigType {
    /**
     * Unstructured text.
     */
    TEXT,
    /**
     * Boolean text.
     */
    BOOLEAN,
    /**
     * Numeric text.
     */
    NUMBER,
    /**
     * JSON source text.
     */
    JSON,
    /**
     * YAML source text.
     */
    YAML,
    /**
     * Java properties source text.
     */
    PROPERTIES,
    /**
     * A format unknown to this SDK version; inspect {@link Config#getRawType()}.
     */
    UNKNOWN
}
