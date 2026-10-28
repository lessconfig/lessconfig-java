package com.lessconfig;

import java.util.Objects;

/**
 * Project identity and display metadata returned by the server.
 * The slug identifies the project in client API calls; the ID is its server-assigned identity.
 */
public class Project {
    /**
     * Server-assigned project ID, distinct from the slug used in API paths.
     */
    private String id;

    /**
     * Case-sensitive project identifier used in client API calls and watch scopes.
     */
    private String slug;

    /**
     * Human-readable project name used for display.
     */
    private String name;

    /**
     * Icon name supplied by the server, such as {@code folder-simple}.
     */
    private String icon;

    /**
     * Human-readable project description supplied by the server.
     */
    private String description;

    /**
     * Creates a project object whose properties can be populated through its setters.
     */
    public Project() {
    }

    /**
     * Returns the server project ID.
     *
     * @return server project ID
     */
    public String getId() {
        return id;
    }

    /**
     * Sets the server project ID on this detached bean.
     *
     * @param id server project ID; never null
     * @throws NullPointerException if the value is null
     */
    public void setId(String id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    /**
     * Returns the project identifier used in client API calls and watch scopes.
     *
     * @return case-sensitive project slug
     */
    public String getSlug() {
        return slug;
    }

    /**
     * Sets the case-sensitive project slug on this detached bean.
     *
     * @param slug case-sensitive project slug; never null
     * @throws NullPointerException if the value is null
     */
    public void setSlug(String slug) {
        this.slug = Objects.requireNonNull(slug, "slug");
    }

    /**
     * Returns the project display name.
     *
     * @return project display name
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the project display name on this detached bean.
     *
     * @param name project display name; never null
     * @throws NullPointerException if the value is null
     */
    public void setName(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    /**
     * Returns the server icon name.
     *
     * @return server icon name
     */
    public String getIcon() {
        return icon;
    }

    /**
     * Sets the server icon name on this detached bean.
     *
     * @param icon server icon name; never null
     * @throws NullPointerException if the value is null
     */
    public void setIcon(String icon) {
        this.icon = Objects.requireNonNull(icon, "icon");
    }

    /**
     * Returns the project description.
     *
     * @return project description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets the project description on this detached bean.
     *
     * @param description project description; never null
     * @throws NullPointerException if the value is null
     */
    public void setDescription(String description) {
        this.description = Objects.requireNonNull(description, "description");
    }
}
