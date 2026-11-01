package dev.planetonotion.services.projects;

import tools.jackson.databind.JsonNode;
import dev.planetonotion.common.config.PlaneProperties;
import dev.planetonotion.common.exceptions.ServiceException;
import dev.planetonotion.integrations.plane.PlaneClient;
import dev.planetonotion.models.documents.PlaneProjectDocument;
import dev.planetonotion.repositories.projects.PlaneProjectRepository;
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

@Service
public class ProjectCatalogService {

    private static final int MONGO_WRITE_BATCH_SIZE = 1000;

    private final PlaneClient planeClient;
    private final PlaneProjectRepository projectRepository;
    private final PlaneProperties planeProperties;

    public ProjectCatalogService(PlaneClient planeClient, PlaneProjectRepository projectRepository, PlaneProperties planeProperties) {
        this.planeClient = planeClient;
        this.projectRepository = projectRepository;
        this.planeProperties = planeProperties;
    }

    public List<PlaneProjectDocument> list(String workspaceSlug) {
        return projectRepository.findByWorkspaceSlugAndIsAvailableTrueAndIsArchivedFalseOrderByNameAsc(
                requiredWorkspaceSlug(workspaceSlug));
    }

    public List<PlaneProjectDocument> refresh(String workspaceSlug) {
        String validatedWorkspaceSlug = requiredWorkspaceSlug(workspaceSlug);
        Instant now = Instant.now();
        List<PlaneProjectDocument> existing = projectRepository.findByWorkspaceSlug(validatedWorkspaceSlug);
        Map<String, PlaneProjectDocument> existingById = new HashMap<>();
        existing.forEach(project -> existingById.put(project.projectId(), project));

        Set<String> seen = new HashSet<>();
        List<PlaneProjectDocument> batch = new ArrayList<>(MONGO_WRITE_BATCH_SIZE);
        planeClient.forEachProjectPage(validatedWorkspaceSlug, page -> {
            for (JsonNode source : page) {
                String projectId = source.path("id").asString("");
                if (projectId.isBlank()) continue;
                seen.add(projectId);
                PlaneProjectDocument old = existingById.get(projectId);
                batch.add(fromPlane(source, validatedWorkspaceSlug, old, now, true, projectId));
                if (batch.size() == MONGO_WRITE_BATCH_SIZE) flush(batch);
            }
        });
        for (PlaneProjectDocument old : existing) {
            if (!seen.contains(old.projectId())) {
                batch.add(new PlaneProjectDocument(old.id(), old.workspaceSlug(), old.projectId(), old.name(),
                        old.identifier(), old.isArchived(), false, old.lastRefreshedAt(), old.createdAt(), now));
                if (batch.size() == MONGO_WRITE_BATCH_SIZE) flush(batch);
            }
        }
        flush(batch);
        return projectRepository.findByWorkspaceSlug(validatedWorkspaceSlug);
    }

    public PlaneProjectDocument resolveProjectUrl(String projectUrl) {
        URI url;
        try {
            url = URI.create(projectUrl);
        } catch (RuntimeException exception) {
            throw invalidProjectUrl();
        }
        if (url.getHost() == null || !url.getHost().equalsIgnoreCase(planeProperties.appBaseUrl().getHost())
                || !("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme()))) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "INVALID_PLANE_HOST", "Enter a project URL from the configured Plane instance.");
        }
        String[] parts = url.getPath().split("/");
        if (parts.length < 4 || parts[1].isBlank() || !"projects".equals(parts[2]) || parts[3].isBlank()) {
            throw invalidProjectUrl();
        }
        String workspaceSlug = parts[1];
        String projectId = parts[3];
        JsonNode source = planeClient.getProject(workspaceSlug, projectId);
        PlaneProjectDocument existing = projectRepository
                .findByWorkspaceSlugAndProjectId(workspaceSlug, source.path("id").asString(projectId)).orElse(null);
        return projectRepository.save(fromPlane(source, workspaceSlug, existing, Instant.now(), true, projectId));
    }

    public PlaneProjectDocument findById(org.bson.types.ObjectId projectRef) {
        return projectRepository.findById(projectRef)
                .orElseThrow(() -> new ServiceException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "The Plane project is not in the catalog."));
    }

    public PlaneProjectDocument refreshProject(ObjectId projectRef) {
        PlaneProjectDocument existing = findById(projectRef);
        JsonNode source = planeClient.getProject(existing.workspaceSlug(), existing.projectId());
        return projectRepository.save(fromPlane(source, existing.workspaceSlug(), existing, Instant.now(), true,
                existing.projectId()));
    }

    public List<PlaneProjectDocument> findAllById(List<ObjectId> projectRefs) {
        return projectRepository.findAllById(projectRefs);
    }

    private PlaneProjectDocument fromPlane(JsonNode source, String workspace, PlaneProjectDocument old,
            Instant now, boolean available, String fallbackProjectId) {
        return new PlaneProjectDocument(
                old == null ? null : old.id(), workspace, source.path("id").asString(fallbackProjectId),
                source.path("name").asString(old == null ? "Unnamed project" : old.name()),
                source.path("identifier").asString(old == null ? "" : old.identifier()),
                isArchived(source, old), available,
                now, old == null ? now : old.createdAt(), now);
    }

    private boolean isArchived(JsonNode source, PlaneProjectDocument old) {
        JsonNode archivedAt = source.path("archived_at");
        if (!archivedAt.isMissingNode()) return !archivedAt.isNull();
        return source.path("is_archived").asBoolean(old != null && old.isArchived());
    }

    private void flush(List<PlaneProjectDocument> batch) {
        if (batch.isEmpty()) return;
        projectRepository.bulkUpsert(batch);
        batch.clear();
    }

    private ServiceException invalidProjectUrl() {
        return new ServiceException(HttpStatus.BAD_REQUEST, "INVALID_PROJECT_URL", "Enter a Plane project URL containing its workspace and project.");
    }

    private String requiredWorkspaceSlug(String workspaceSlug) {
        if (workspaceSlug == null || workspaceSlug.isBlank()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "WORKSPACE_SLUG_REQUIRED", "Enter a Plane workspace slug.");
        }
        return workspaceSlug.trim();
    }
}
