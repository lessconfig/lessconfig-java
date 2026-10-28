package com.lessconfig;

import java.util.Objects;

/**
 * Internal identity of a configuration key or an entire environment subscription.
 * A null key selects every configuration in the identified environment.
 */
class ConfigId {
    /**
     * Case-sensitive project slug used in API paths and watch scopes.
     */
    private final String project;

    /**
     * Case-sensitive environment slug within the project.
     */
    private final String environment;

    /**
     * Configuration key, or null to select the entire environment.
     */
    private final String key;

    /**
     * Creates an identity from validated public resource identifiers.
     *
     * @param project     project slug
     * @param environment environment slug
     * @param key         configuration key, or null for an environment subscription
     * @throws IllegalArgumentException if a supplied identifier violates the server identifier rules
     */
    ConfigId(String project, String environment, String key) {
        this.project = identifier(project, "project");
        this.environment = identifier(environment, "environment");
        this.key = key == null ? null : identifier(key, "key");
    }

    /**
     * Validates an identifier without changing its case or contents.
     * Allowed characters are ASCII letters, digits, dots, hyphens and underscores.
     *
     * @param value identifier to validate
     * @param name  parameter name included in an invalid-identifier error
     * @return the original identifier when valid
     * @throws IllegalArgumentException if the identifier is null, empty, contains unsupported characters,
     *                                  or equals the reserved path segment {@code .} or {@code ..}
     */
    static String identifier(String value, String name) {
        if (value == null || !value.matches("[A-Za-z0-9._-]+") || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException(name + " must be a valid LessConfig identifier");
        }
        return value;
    }

    /**
     * Formats the identity as a public watch scope.
     *
     * @return {@code project/environment/key}, or {@code project/environment} for an environment subscription
     */
    String scope() {
        return project + "/" + environment + (key == null ? "" : "/" + key);
    }

    /**
     * Checks whether this subscription includes another identity.
     * An environment subscription covers every key in the same environment; a key covers only itself.
     *
     * @param other identity to check
     * @return true when this identity covers the supplied key or environment
     */
    boolean covers(ConfigId other) {
        return project.equals(other.project) && environment.equals(other.environment)
                && (key == null || key.equals(other.key));
    }

    /**
     * Returns the project identifier.
     *
     * @return case-sensitive project slug
     */
    public String getProject() {
        return project;
    }

    /**
     * Returns the environment identifier within the project.
     *
     * @return case-sensitive environment slug
     */
    public String getEnvironment() {
        return environment;
    }

    /**
     * Returns the selected configuration key.
     *
     * @return configuration key, or null for an environment subscription
     */
    public String getKey() {
        return key;
    }

    /**
     * Compares project, environment and key using case-sensitive equality.
     *
     * @param o object to compare with this identity
     * @return true for an identity of the same class with equal project, environment and key
     */
    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        ConfigId configId = (ConfigId) o;
        return Objects.equals(project, configId.project) &&
                Objects.equals(environment, configId.environment) &&
                Objects.equals(key, configId.key);
    }

    /**
     * Computes a hash from the same fields used by {@link #equals(Object)}.
     *
     * @return hash of the project, environment and key
     */
    @Override
    public int hashCode() {
        return Objects.hash(project, environment, key);
    }
}
