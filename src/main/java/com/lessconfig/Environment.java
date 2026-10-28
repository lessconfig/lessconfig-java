package com.lessconfig;

import java.util.Objects;

/**
 * Environment identity and metadata within a project.
 * Client API calls identify an environment by its project slug and environment slug.
 */
public class Environment {
    /**
     * Server-assigned environment ID, distinct from its public slug.
     */
    private String id;

    /**
     * Server-assigned ID of the project that owns this environment.
     */
    private String projectId;

    /**
     * Case-sensitive environment identifier within its project.
     */
    private String slug;

    /**
     * Human-readable environment description supplied by the server.
     */
    private String description;

    /**
     * Environment resource revision supplied by the server, separate from global watch progress.
     */
    private Long revision;

    /**
     * Creates an environment object whose properties can be populated through its setters.
     */
    public Environment() {
    }

    /**
     * Returns the server environment ID.
     *
     * @return server environment ID
     */
    public String getId() {
        return id;
    }

    /**
     * Sets the server environment ID on this detached bean.
     *
     * @param id server environment ID; never null
     * @throws NullPointerException if the value is null
     */
    public void setId(String id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    /**
     * Returns the server-assigned ID of the owning project.
     *
     * @return owning project ID, distinct from the project slug
     */
    public String getProjectId() {
        return projectId;
    }

    /**
     * Sets the owning project ID on this detached bean.
     *
     * @param projectId owning project ID; never null
     * @throws NullPointerException if the value is null
     */
    public void setProjectId(String projectId) {
        this.projectId = Objects.requireNonNull(projectId, "projectId");
    }

    /**
     * Returns the environment identifier used together with a project slug in client API calls.
     *
     * @return case-sensitive environment slug
     */
    public String getSlug() {
        return slug;
    }

    /**
     * Sets the case-sensitive environment slug on this detached bean.
     *
     * @param slug case-sensitive environment slug; never null
     * @throws NullPointerException if the value is null
     */
    public void setSlug(String slug) {
        this.slug = Objects.requireNonNull(slug, "slug");
    }

    /**
     * Returns the environment description.
     *
     * @return environment description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets the environment description on this detached bean.
     *
     * @param description environment description; never null
     * @throws NullPointerException if the value is null
     */
    public void setDescription(String description) {
        this.description = Objects.requireNonNull(description, "description");
    }

    /**
     * Returns the environment resource revision supplied by the server.
     * The client tracks global watch progress separately from environment revisions.
     *
     * @return environment resource revision
     */
    public Long getRevision() {
        return revision;
    }

    /**
     * Sets the revision associated with this environment object.
     *
     * @param revision environment resource revision; never null
     * @throws NullPointerException if the value is null
     */
    public void setRevision(Long revision) {
        this.revision = Objects.requireNonNull(revision, "revision");
    }
}
