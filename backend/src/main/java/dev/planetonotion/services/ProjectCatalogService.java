package dev.planetonotion.services;

import dev.planetonotion.common.ProjectDefaults;
import dev.planetonotion.common.ApiErrorCodes;
import dev.planetonotion.common.exceptions.ServiceException;
import dev.planetonotion.common.IntegrationProperties;
import dev.planetonotion.integrations.PlaneClient;
import dev.planetonotion.integrations.models.PlaneApiModels.Project;
import dev.planetonotion.models.documents.PlaneProjectDocument;
import dev.planetonotion.repositories.MongoQueryOptions.Filter;
import dev.planetonotion.repositories.MongoQueryOptions.Sort;
import dev.planetonotion.repositories.MongoQueryOptions;
import dev.planetonotion.repositories.PlaneProjectRepository;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import static dev.planetonotion.integrations.PlaneApiConstants.TaskUrl.HTTP_SCHEME;
import static dev.planetonotion.integrations.PlaneApiConstants.TaskUrl.HTTPS_SCHEME;
import static dev.planetonotion.integrations.PlaneApiConstants.TaskUrl.PROJECTS_SEGMENT;

/**
 * Lists and refreshes the local catalog of Plane projects used by sync configs.
 */
@Service
public class ProjectCatalogService {

    private static final int MONGO_WRITE_BATCH_SIZE = 1000;

    private final PlaneClient planeClient;
    private final PlaneProjectRepository projectRepository;
    private final IntegrationProperties integrationProperties;

    /** Creates the catalog service with Plane access and project persistence. */
    public ProjectCatalogService(PlaneClient planeClient,
            PlaneProjectRepository projectRepository, IntegrationProperties integrationProperties) {
        this.planeClient = planeClient;
        this.projectRepository = projectRepository;
        this.integrationProperties = integrationProperties;
    }

    /**
     * Lists selectable projects in a workspace by project name.
     *
     * @param workspaceSlug Plane workspace slug
     * @return available, non-archived projects
     */
    public List<PlaneProjectDocument> list(String workspaceSlug) {
        MongoQueryOptions options = new MongoQueryOptions(
                List.of(
                        Filter.equalTo(PlaneProjectDocument.FIELD_IS_AVAILABLE, true),
                        Filter.equalTo(PlaneProjectDocument.FIELD_IS_ARCHIVED_IN_PLANE, false)),
                List.of(Sort.ascending(PlaneProjectDocument.FIELD_PLANE_PROJECT_NAME)));
        return projectRepository.findByPlaneWorkspaceSlug(requiredWorkspaceSlug(workspaceSlug), options);
    }

    /**
     * Refreshes all projects in a workspace from Plane and marks missing ones
     * unavailable in the local catalog.
     *
     * @param workspaceSlug Plane workspace slug
     * @return refreshed catalog records for the workspace
     */
    public List<PlaneProjectDocument> refresh(String workspaceSlug) {
        // Validate workspace and capture refresh time.
        String validatedWorkspaceSlug = requiredWorkspaceSlug(workspaceSlug);
        Instant now = Instant.now();
        List<PlaneProjectDocument> existing = projectRepository.findByPlaneWorkspaceSlug(validatedWorkspaceSlug,
                MongoQueryOptions.empty());
        // Map existing records by Plane ID.
        Map<String, PlaneProjectDocument> existingById = new HashMap<>();
        existing.forEach(project -> existingById.put(project.planeProjectId(), project));

        // Upsert fetched projects in bounded batches.
        Set<String> seen = new HashSet<>();
        List<PlaneProjectDocument> batch = new ArrayList<>(MONGO_WRITE_BATCH_SIZE);
        planeClient.forEachProjectPage(validatedWorkspaceSlug, page -> {
            for (Project source : page) {
                String projectId = source.id();
                if (projectId.isBlank())
                    continue;
                seen.add(projectId);
                PlaneProjectDocument old = existingById.get(projectId);
                batch.add(fromPlane(source, validatedWorkspaceSlug, old, now, true, projectId));
                if (batch.size() == MONGO_WRITE_BATCH_SIZE)
                    flush(batch);
            }
        });
        // Mark missing Plane projects unavailable.
        for (PlaneProjectDocument old : existing) {
            if (!seen.contains(old.planeProjectId())) {
                batch.add(new PlaneProjectDocument(old.id(), old.planeWorkspaceSlug(), old.planeProjectId(),
                        old.planeProjectName(),
                        old.planeProjectIdentifier(), old.isArchivedInPlane(), false, old.notionProjectPageId(),
                        old.notionDatabaseId(),
                        old.notionDataSourceId(), old.createdAt(), now));
                if (batch.size() == MONGO_WRITE_BATCH_SIZE)
                    flush(batch);
            }
        }
        // Flush remaining records and return catalog.
        flush(batch);
        return projectRepository.findByPlaneWorkspaceSlug(validatedWorkspaceSlug, MongoQueryOptions.empty());
    }

    /**
     * Validates a Plane project URL, fetches its canonical project data, and
     * saves or updates that project in the local catalog.
     *
     * @param projectUrl URL containing the workspace slug and project ID
     * @return the saved catalog project
     */
    public PlaneProjectDocument resolveProjectUrl(String projectUrl) {
        // Parse and validate the project URL.
        URI url;
        try {
            url = URI.create(projectUrl);
        } catch (RuntimeException exception) {
            throw invalidProjectUrl();
        }
        // Validate Plane host and URL scheme.
        if (url.getHost() == null || !url.getHost().equalsIgnoreCase(integrationProperties.planeAppBaseUrl().getHost())
                || !(HTTPS_SCHEME.equalsIgnoreCase(url.getScheme()) || HTTP_SCHEME.equalsIgnoreCase(url.getScheme()))) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, ApiErrorCodes.Plane.INVALID_HOST,
                    "Enter a project URL from the configured Plane instance.");
        }
        // Extract workspace and project path identifiers.
        String[] parts = url.getPath().split("/");
        if (parts.length < 4 || parts[1].isBlank() || !PROJECTS_SEGMENT.equals(parts[2]) || parts[3].isBlank()) {
            throw invalidProjectUrl();
        }
        // Fetch and save the Plane project.
        String workspaceSlug = parts[1];
        String projectId = parts[3];
        Project source = planeClient.getProject(workspaceSlug, projectId);
        PlaneProjectDocument existing = projectRepository
                .findByPlaneWorkspaceSlugAndPlaneProjectId(workspaceSlug,
                        source.id() == null ? projectId : source.id())
                .orElse(null);
        return projectRepository.save(fromPlane(source, workspaceSlug, existing, Instant.now(), true, projectId));
    }

    /**
     * Finds a catalog project by its database ID.
     *
     * @param projectRef project MongoDB ID
     * @return the matching project
     */
    public PlaneProjectDocument findById(ObjectId projectRef) {
        return projectRepository.findById(projectRef)
                .orElseThrow(() -> new ServiceException(HttpStatus.NOT_FOUND,
                        ApiErrorCodes.Application.PROJECT_NOT_FOUND,
                        "The Plane project is not in the catalog."));
    }

    /**
     * Refreshes one catalog project's details from Plane.
     *
     * @param projectRef project MongoDB ID
     * @return the refreshed project record
     */
    public PlaneProjectDocument refreshProject(ObjectId projectRef) {
        // Refresh one catalog record from Plane.
        PlaneProjectDocument existing = findById(projectRef);
        Project source = planeClient.getProject(existing.planeWorkspaceSlug(), existing.planeProjectId());
        return projectRepository.save(fromPlane(source, existing.planeWorkspaceSlug(), existing, Instant.now(), true,
                existing.planeProjectId()));
    }

    /**
     * Loads catalog projects for the supplied database IDs.
     *
     * @param projectRefs project MongoDB IDs
     * @return matching project records
     */
    public List<PlaneProjectDocument> findAllById(List<ObjectId> projectRefs) {
        return projectRepository.findAllById(projectRefs);
    }

    /** Maps Plane fields while retaining existing destination and creation data. */
    private PlaneProjectDocument fromPlane(Project source, String workspace, PlaneProjectDocument old,
            Instant now, boolean available, String fallbackProjectId) {
        return new PlaneProjectDocument(
                old == null ? null : old.id(), workspace, source.id() == null ? fallbackProjectId : source.id(),
                source.name() == null
                        ? (old == null ? ProjectDefaults.UNNAMED_PROJECT : old.planeProjectName())
                        : source.name(),
                source.identifier() == null ? (old == null ? "" : old.planeProjectIdentifier()) : source.identifier(),
                isArchived(source, old), available,
                old == null ? null : old.notionProjectPageId(),
                old == null ? null : old.notionDatabaseId(),
                old == null ? null : old.notionDataSourceId(),
                old == null ? now : old.createdAt(), now);
    }

    /** Determines archive state, retaining stored state if Plane omits it. */
    private boolean isArchived(Project source, PlaneProjectDocument old) {
        if (source.archivedAt() != null)
            return true;
        return source.isArchived() == null ? old != null && old.isArchivedInPlane() : source.isArchived();
    }

    /** Bulk-upserts a nonempty batch and clears it after writing. */
    private void flush(List<PlaneProjectDocument> batch) {
        if (batch.isEmpty())
            return;
        projectRepository.bulkUpsert(batch);
        batch.clear();
    }

    /** Creates the standard bad-request exception for malformed project URLs. */
    private ServiceException invalidProjectUrl() {
        return new ServiceException(HttpStatus.BAD_REQUEST, ApiErrorCodes.Application.INVALID_PROJECT_URL,
                "Enter a Plane project URL containing its workspace and project.");
    }

    /** Trims the workspace slug or raises its required-field error. */
    private String requiredWorkspaceSlug(String workspaceSlug) {
        if (workspaceSlug == null || workspaceSlug.isBlank()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, ApiErrorCodes.Application.WORKSPACE_SLUG_REQUIRED,
                    "Enter a Plane workspace slug.");
        }
        return workspaceSlug.trim();
    }
}
