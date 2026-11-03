package dev.planetonotion.controllers;

import dev.planetonotion.integrations.PlaneClient;
import dev.planetonotion.models.documents.PlaneProjectDocument;
import dev.planetonotion.models.ApiDtos.StatusResponse;
import dev.planetonotion.models.ApiDtos.ProjectRefreshRequest;
import dev.planetonotion.models.ApiDtos.ProjectRefreshResponse;
import dev.planetonotion.models.ApiDtos.ProjectResponse;
import dev.planetonotion.models.ApiDtos.ProjectUrlRequest;
import dev.planetonotion.services.ProjectCatalogService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP endpoints for testing Plane access and managing the project catalog. */
@RestController
@RequestMapping("/api/plane")
public class ProjectController {

    private final PlaneClient planeClient;
    private final ProjectCatalogService projectCatalogService;

    /** Creates the controller with Plane access and catalog services. */
    public ProjectController(PlaneClient planeClient, ProjectCatalogService projectCatalogService) {
        this.planeClient = planeClient;
        this.projectCatalogService = projectCatalogService;
    }

    /**
     * Checks Plane credentials and API connectivity.
     *
     * @return connection status response
     */
    @PostMapping("/connection/test")
    public StatusResponse testConnection() {
        planeClient.testConnection();
        return new StatusResponse("CONNECTED");
    }

    /**
     * Lists selectable projects for a workspace.
     *
     * @param workspaceSlug Plane workspace slug
     * @return available project responses
     */
    @GetMapping("/projects")
    public List<ProjectResponse> listProjects(@RequestParam("workspace_slug") String workspaceSlug) {
        return projectCatalogService.list(workspaceSlug).stream().map(this::toResponse).toList();
    }

    /**
     * Refreshes the local catalog from Plane.
     *
     * @param request workspace to refresh
     * @return refreshed projects and refresh time
     */
    @PostMapping("/projects/refresh")
    public ProjectRefreshResponse refreshProjects(@Valid @RequestBody ProjectRefreshRequest request) {
        List<ProjectResponse> projects = projectCatalogService.refresh(request.workspaceSlug()).stream()
                .map(this::toResponse).toList();
        return new ProjectRefreshResponse(request.workspaceSlug(), Instant.now(), projects);
    }

    /**
     * Resolves and caches a project from its URL.
     *
     * @param request project URL to resolve
     * @return resolved project response
     */
    @PostMapping("/projects/resolve")
    public ProjectResponse resolveProject(@Valid @RequestBody ProjectUrlRequest request) {
        return toResponse(projectCatalogService.resolveProjectUrl(request.projectUrl()));
    }

    /** Maps a catalog project to its API response. */
    private ProjectResponse toResponse(PlaneProjectDocument project) {
        return new ProjectResponse(project.id().toHexString(), project.planeWorkspaceSlug(), project.planeProjectId(),
                project.planeProjectName(), project.planeProjectIdentifier(),
                planeClient.projectUrl(project.planeWorkspaceSlug(), project.planeProjectId()),
                project.isArchivedInPlane(), project.isAvailable());
    }
}
