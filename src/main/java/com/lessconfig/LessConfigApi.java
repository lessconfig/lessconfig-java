package com.lessconfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import static com.lessconfig.ConfigChange.CONFIG_CHANGE_RESOURCE_CONFIG;
import static com.lessconfig.ConfigChange.CONFIG_CHANGE_RESOURCE_ENVIRONMENT;
import static com.lessconfig.ConfigChange.CONFIG_CHANGE_RESOURCE_PROJECT;
import static com.lessconfig.ConfigChange.CONFIG_CHANGE_TYPE_CREATED;
import static com.lessconfig.ConfigChange.CONFIG_CHANGE_TYPE_DELETED;
import static com.lessconfig.ConfigChange.CONFIG_CHANGE_TYPE_RESTORED;
import static com.lessconfig.ConfigChange.CONFIG_CHANGE_TYPE_UPDATED;

/**
 * Maps the LessConfig API and validates responses before they can update the cache.
 */
final class LessConfigApi implements AutoCloseable {
    private final HttpTransport transport;
    private final Duration readTimeout;

    /**
     * Creates an independent HTTP API adapter.
     *
     * @param endpoint       complete API base URL
     * @param token          Bearer credential
     * @param connectTimeout connection establishment timeout
     * @param readTimeout    ordinary request socket-read timeout
     * @throws IllegalArgumentException if endpoint or token is invalid
     */
    LessConfigApi(String endpoint, String token, Duration connectTimeout, Duration readTimeout) {
        transport = new HttpTransport(endpoint, token, connectTimeout);
        this.readTimeout = readTimeout;
    }

    /**
     * Reads one configuration directly from the server, preserving its raw text.
     *
     * @param id validated project/environment/key identity
     * @return decoded configuration, or null only for config_not_found
     * @throws LessConfigException on transport/protocol failure or missing/unauthorized ancestors
     */
    @Nullable Config getConfig(ConfigId id) {
        return missing(() -> config(transport.get(environmentPath(id.getProject(), id.getEnvironment())
                + "/configurations/" + id.getKey(), readTimeout)), "config_not_found");
    }

    /**
     * Reads every current configuration in one environment in server order.
     *
     * @param project     project slug
     * @param environment environment slug
     * @return unmodifiable configuration list, possibly empty; never null
     * @throws LessConfigException if the request or response decoding fails
     */
    @NonNull List<Config> getConfigs(String project, String environment) {
        return decode(() -> items(transport.get(environmentPath(project, environment) + "/configurations",
                readTimeout), LessConfigApi::config));
    }

    /**
     * Reads current environment metadata.
     *
     * @param project     project slug
     * @param environment environment slug
     * @return metadata, or null if the project/environment is absent
     * @throws LessConfigException if the request fails for another reason
     */
    @Nullable Environment getEnvironment(String project, String environment) {
        return missing(() -> environment(transport.get(environmentPath(project, environment), readTimeout)),
                "environment_not_found", "project_not_found");
    }

    /**
     * Lists environments visible to the token under one project.
     *
     * @param project project slug
     * @return unmodifiable list in server order, possibly empty; never null
     * @throws LessConfigException if the request fails, including a missing project
     */
    @NonNull List<Environment> getEnvironments(String project) {
        return decode(() -> items(transport.get(projectPath(project) + "/environments", readTimeout),
                LessConfigApi::environment));
    }

    /**
     * Reads current project metadata.
     *
     * @param project project slug
     * @return project metadata, or null if the project is absent
     * @throws LessConfigException if the request fails for another reason
     */
    @Nullable Project getProject(String project) {
        return missing(() -> project(transport.get(projectPath(project), readTimeout)), "project_not_found");
    }

    /**
     * Lists projects visible to the token.
     *
     * @return unmodifiable list in server order; an empty/null items array becomes an empty list, never null
     * @throws LessConfigException if the request or response decoding fails
     */
    @NonNull List<Project> getProjects() {
        return decode(() -> items(transport.get("/projects", readTimeout), LessConfigApi::project));
    }

    /**
     * Initializes a watch when revision is absent, otherwise performs one long poll.
     * The returned watermark is not acknowledged here; the caller must first apply all changes.
     *
     * @param scopes      nonempty, validated selection of keys/environments
     * @param revision    last successfully applied global watermark, or null to initialize
     * @param timeout     server long-poll duration; whole seconds between one and three hundred
     * @param readTimeout client socket-read timeout for this request
     * @return validated watermark and immutable change records
     * @throws LessConfigException on HTTP failure, malformed changes or a backward watermark
     */
    WatchResult watch(Collection<ConfigId> scopes, @Nullable Long revision, Duration timeout, Duration readTimeout) {
        StringBuilder path = new StringBuilder("/watch?");
        for (ConfigId scope : scopes) {
            if (path.length() > 7) {
                path.append('&');
            }
            try {
                path.append("scopes=").append(URLEncoder.encode(scope.scope(), "UTF-8"));
            } catch (UnsupportedEncodingException impossible) {
                throw new AssertionError(impossible);
            }
        }
        if (revision != null) {
            path.append("&revision=").append(revision).append("&timeout=").append(timeout.getSeconds());
        }
        return decode(() -> {
            JSONObject json = transport.get(path.toString(), readTimeout);
            long next = revision(json);
            if (revision != null && next < revision) {
                throw new JSONException("Watch revision moved backwards");
            }
            JSONArray entries = json.getJSONArray("changes");
            List<ConfigChange> changes = new ArrayList<>();
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                long eventRevision = revision(entry);
                String resource = entry.getString("resource");
                String type = entry.getString("type");
                String project = ConfigId.identifier(entry.getString("project"), "project");
                String environment = entry.isNull("environment") ? null
                        : ConfigId.identifier(entry.getString("environment"), "environment");
                String key = entry.isNull("key") ? null : ConfigId.identifier(entry.getString("key"), "key");
                if (eventRevision > next || (revision != null && eventRevision <= revision)
                        || !(CONFIG_CHANGE_RESOURCE_CONFIG.equals(resource)
                            || CONFIG_CHANGE_RESOURCE_ENVIRONMENT.equals(resource)
                            || CONFIG_CHANGE_RESOURCE_PROJECT.equals(resource))
                        || !(CONFIG_CHANGE_TYPE_CREATED.equals(type) || CONFIG_CHANGE_TYPE_UPDATED.equals(type)
                            || CONFIG_CHANGE_TYPE_DELETED.equals(type) || CONFIG_CHANGE_TYPE_RESTORED.equals(type))
                        || (CONFIG_CHANGE_RESOURCE_CONFIG.equals(resource) && (environment == null || key == null))
                        || (CONFIG_CHANGE_RESOURCE_ENVIRONMENT.equals(resource) && (environment == null || key != null))
                        || (CONFIG_CHANGE_RESOURCE_PROJECT.equals(resource) && (environment != null || key != null))) {
                    throw new JSONException("Invalid watch change");
                }
                changes.add(new ConfigChange(eventRevision, project, environment, key, resource, type));
            }
            if (revision == null && !changes.isEmpty()) {
                throw new JSONException("Watch initialization must return no changes");
            }
            return new WatchResult(next, changes);
        });
    }

    /**
     * Builds the path for a validated project slug.
     *
     * @param project project slug
     * @return relative API path
     * @throws IllegalArgumentException if the identifier is invalid
     */
    private static String projectPath(String project) {
        return "/projects/" + ConfigId.identifier(project, "project");
    }

    /**
     * Builds the path for a validated project/environment pair.
     *
     * @param project     project slug
     * @param environment environment slug
     * @return relative API path
     * @throws IllegalArgumentException if an identifier is invalid
     */
    private static String environmentPath(String project, String environment) {
        return projectPath(project) + "/environments/" + ConfigId.identifier(environment, "environment");
    }

    /**
     * Decodes an integer revision within the server's JavaScript safe-integer range.
     *
     * @param json response object containing revision
     * @return nonnegative global or resource revision
     * @throws JSONException if the value is missing, fractional, negative or out of range
     */
    private static long revision(JSONObject json) {
        Object value = json.get("revision");
        if (!(value instanceof Number) || !value.toString().matches("[0-9]+")) {
            throw new JSONException("Invalid revision");
        }
        long revision = json.getLong("revision");
        if (revision < 0 || revision > 9007199254740991L) {
            throw new JSONException("Revision is outside the server's safe integer range");
        }
        return revision;
    }

    /**
     * Decodes a configuration, preserving the server's type name without enum conversion or normalization.
     *
     * @param json configuration response object
     * @return independent configuration bean
     * @throws JSONException if required fields are missing or invalid
     */
    private static Config config(JSONObject json) {
        Config result = new Config();
        result.setKey(json.getString("key"));
        result.setValue(json.getString("value"));
        result.setType(json.getString("type"));
        result.setDescription(json.getString("description"));
        result.setRevision(revision(json));
        return result;
    }

    /**
     * Decodes environment metadata.
     *
     * @param json environment response object
     * @return independent metadata bean
     * @throws JSONException if required fields are missing or invalid
     */
    private static Environment environment(JSONObject json) {
        Environment result = new Environment();
        result.setId(json.getString("id"));
        result.setProjectId(json.getString("project_id"));
        result.setSlug(json.getString("slug"));
        result.setDescription(json.getString("description"));
        result.setRevision(revision(json));
        return result;
    }

    /**
     * Decodes project metadata.
     *
     * @param json project response object
     * @return independent metadata bean
     * @throws JSONException if required fields are missing or invalid
     */
    private static Project project(JSONObject json) {
        Project result = new Project();
        result.setId(json.getString("id"));
        result.setSlug(json.getString("slug"));
        result.setName(json.getString("name"));
        result.setIcon(json.getString("icon"));
        result.setDescription(json.getString("description"));
        return result;
    }

    /**
     * Converts a collection envelope to a non-null, unmodifiable list without reordering it.
     *
     * @param json   response envelope with an items property
     * @param mapper parser for each item
     * @param <T>    item type
     * @return decoded items, or an empty list when the items property is null/empty
     * @throws JSONException if the envelope or an item is malformed
     */
    private static <T> List<T> items(JSONObject json, Function<JSONObject, T> mapper) {
        if (json.has("items") && json.isNull("items")) {
            return Collections.emptyList();
        }
        JSONArray items = json.getJSONArray("items");
        List<T> result = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            result.add(mapper.apply(items.getJSONObject(i)));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Parses one response.
     *
     * @param <T> decoded response type
     */
    private interface Decoder<T> {
        /**
         * Reads and parses the response.
         *
         * @return non-null decoded response
         */
        T read();
    }

    /**
     * Decodes a required response and converts malformed payloads to SDK exceptions.
     *
     * @param decoder response parser
     * @param <T>     required response type
     * @return parsed, non-null response
     * @throws LessConfigException if transport, server or decoding fails
     */
    private static <T> T decode(Decoder<T> decoder) {
        try {
            return decoder.read();
        } catch (JSONException | IllegalArgumentException e) {
            throw new LessConfigException("Invalid API response", e);
        }
    }

    /**
     * Reads an optional resource, converting only recognized not-found responses to absence.
     *
     * @param decoder      response parser
     * @param missingCodes business codes that represent an absent resource
     * @param <T>          resource type
     * @return parsed resource, or null for a recognized HTTP 404 response
     * @throws LessConfigException if the request fails for another reason
     */
    private static <T> @Nullable T missing(Decoder<T> decoder, String... missingCodes) {
        try {
            return decode(decoder);
        } catch (LessConfigException e) {
            if (e.getStatusCode() == 404) {
                for (String code : missingCodes) {
                    if (code.equals(e.getCode())) {
                        return null;
                    }
                }
            }
            throw e;
        }
    }

    /**
     * Cancels in-flight requests while keeping this adapter reusable.
     */
    void cancel() {
        transport.cancel();
    }

    /**
     * Permanently closes the adapter and cancels its outstanding requests.
     */
    @Override
    public void close() {
        transport.close();
    }

    /**
     * An immutable watch result whose watermark is committed only after successful cache application.
     */
    static final class WatchResult {
        final long revision;
        final List<ConfigChange> changes;

        /**
         * Copies the event list into an immutable result.
         *
         * @param revision global response watermark
         * @param changes  validated server change records
         */
        WatchResult(long revision, List<ConfigChange> changes) {
            this.revision = revision;
            this.changes = Collections.unmodifiableList(new ArrayList<>(changes));
        }
    }
}
