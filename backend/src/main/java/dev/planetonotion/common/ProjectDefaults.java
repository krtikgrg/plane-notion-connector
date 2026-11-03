package dev.planetonotion.common;

/**
 * Fallback values used when Plane project metadata is incomplete or missing.
 */
public final class ProjectDefaults {

    private ProjectDefaults() {
    }

    /**
     * Project name used when Plane omits a name and no cached name is available.
     */
    public static final String UNNAMED_PROJECT = "Unnamed project";
}
