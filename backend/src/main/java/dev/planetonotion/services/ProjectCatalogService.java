package dev.planetonotion.services;

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

@Service
public class ProjectCatalogService {

    private static final int MONGO_WRITE_BATCH_SIZE = 1000;

    private final PlaneClient planeClient;
    private final PlaneProjectRepository projectRepository;
    private final IntegrationProperties integrationProperties;

    public ProjectCatalogService(PlaneClient planeClient,
            PlaneProjectRepository projectRepository, IntegrationProperties integrationProperties) {
        this.planeClient = planeClient;
        this.projectRepository = projectRepository;
        this.integrationProperties = integrationProperties;
    }

    public List<PlaneProjectDocument> list(String workspaceSlug) {
        MongoQueryOptions options = new MongoQueryOptions(
                List.of(
                        Filter.equalTo(PlaneProjectDocument.FIELD_IS_AVAILABLE, true),
                        Filter.equalTo(PlaneProjectDocument.FIELD_IS_ARCHIVED_IN_PLANE, false)),
                List.of(Sort.ascending(PlaneProjectDocument.FIELD_PLANE_PROJECT_NAME)));
        return projectRepository.findByPlaneWorkspaceSlug(requiredWorkspaceSlug(workspaceSlug), options);
    }

    public List<PlaneProjectDocument> refresh(String workspaceSlug) {
        String validatedWorkspaceSlug = requiredWorkspaceSlug(workspaceSlug);
        Instant now = Instant.now();
        List<PlaneProjectDocument> existing = projectRepository.findByPlaneWorkspaceSlug(validatedWorkspaceSlug,
                MongoQueryOptions.empty());
        Map<String, PlaneProjectDocument> existingById = new HashMap<>();
        existing.forEach(project -> existingById.put(project.planeProjectId(), project));

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
        flush(batch);
        return projectRepository.findByPlaneWorkspaceSlug(validatedWorkspaceSlug, MongoQueryOptions.empty());
    }

    public PlaneProjectDocument resolveProjectUrl(String projectUrl) {
        URI url;
        try {
            url = URI.create(projectUrl);
        } catch (RuntimeException exception) {
            throw invalidProjectUrl();
        }
        if (url.getHost() == null || !url.getHost().equalsIgnoreCase(integrationProperties.planeAppBaseUrl().getHost())
                || !("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme()))) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "INVALID_PLANE_HOST",
                    "Enter a project URL from the configured Plane instance.");
        }
        String[] parts = url.getPath().split("/");
        if (parts.length < 4 || parts[1].isBlank() || !"projects".equals(parts[2]) || parts[3].isBlank()) {
            throw invalidProjectUrl();
        }
        String workspaceSlug = parts[1];
        String projectId = parts[3];
        Project source = planeClient.getProject(workspaceSlug, projectId);
        PlaneProjectDocument existing = projectRepository
                .findByPlaneWorkspaceSlugAndPlaneProjectId(workspaceSlug,
                        source.id() == null ? projectId : source.id())
                .orElse(null);
        return projectRepository.save(fromPlane(source, workspaceSlug, existing, Instant.now(), true, projectId));
    }

    public PlaneProjectDocument findById(ObjectId projectRef) {
        return projectRepository.findById(projectRef)
                .orElseThrow(() -> new ServiceException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND",
                        "The Plane project is not in the catalog."));
    }

    public PlaneProjectDocument refreshProject(ObjectId projectRef) {
        PlaneProjectDocument existing = findById(projectRef);
        Project source = planeClient.getProject(existing.planeWorkspaceSlug(), existing.planeProjectId());
        return projectRepository.save(fromPlane(source, existing.planeWorkspaceSlug(), existing, Instant.now(), true,
                existing.planeProjectId()));
    }

    public List<PlaneProjectDocument> findAllById(List<ObjectId> projectRefs) {
        return projectRepository.findAllById(projectRefs);
    }

    private PlaneProjectDocument fromPlane(Project source, String workspace, PlaneProjectDocument old,
            Instant now, boolean available, String fallbackProjectId) {
        return new PlaneProjectDocument(
                old == null ? null : old.id(), workspace, source.id() == null ? fallbackProjectId : source.id(),
                source.name() == null ? (old == null ? "Unnamed project" : old.planeProjectName()) : source.name(),
                source.identifier() == null ? (old == null ? "" : old.planeProjectIdentifier()) : source.identifier(),
                isArchived(source, old), available,
                old == null ? null : old.notionProjectPageId(),
                old == null ? null : old.notionDatabaseId(),
                old == null ? null : old.notionDataSourceId(),
                old == null ? now : old.createdAt(), now);
    }

    private boolean isArchived(Project source, PlaneProjectDocument old) {
        if (source.archivedAt() != null)
            return true;
        return source.isArchived() == null ? old != null && old.isArchivedInPlane() : source.isArchived();
    }

    private void flush(List<PlaneProjectDocument> batch) {
        if (batch.isEmpty())
            return;
        projectRepository.bulkUpsert(batch);
        batch.clear();
    }

    private ServiceException invalidProjectUrl() {
        return new ServiceException(HttpStatus.BAD_REQUEST, "INVALID_PROJECT_URL",
                "Enter a Plane project URL containing its workspace and project.");
    }

    private String requiredWorkspaceSlug(String workspaceSlug) {
        if (workspaceSlug == null || workspaceSlug.isBlank()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "WORKSPACE_SLUG_REQUIRED",
                    "Enter a Plane workspace slug.");
        }
        return workspaceSlug.trim();
    }
}
