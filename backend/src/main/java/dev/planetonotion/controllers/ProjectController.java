package dev.planetonotion.controllers;

import dev.planetonotion.services.PlaneService;
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

@RestController
@RequestMapping("/api/plane")
public class ProjectController {

    private final PlaneService planeService;
    private final ProjectCatalogService projectCatalogService;

    public ProjectController(PlaneService planeService, ProjectCatalogService projectCatalogService) {
        this.planeService = planeService;
        this.projectCatalogService = projectCatalogService;
    }

    @PostMapping("/connection/test")
    public StatusResponse testConnection() {
        planeService.testConnection();
        return new StatusResponse("CONNECTED");
    }

    @GetMapping("/projects")
    public List<ProjectResponse> listProjects(@RequestParam("workspace_slug") String workspaceSlug) {
        return projectCatalogService.list(workspaceSlug).stream().map(this::toResponse).toList();
    }

    @PostMapping("/projects/refresh")
    public ProjectRefreshResponse refreshProjects(@Valid @RequestBody ProjectRefreshRequest request) {
        List<ProjectResponse> projects = projectCatalogService.refresh(request.workspaceSlug()).stream()
                .map(this::toResponse).toList();
        return new ProjectRefreshResponse(request.workspaceSlug(), Instant.now(), projects);
    }

    @PostMapping("/projects/resolve")
    public ProjectResponse resolveProject(@Valid @RequestBody ProjectUrlRequest request) {
        return toResponse(projectCatalogService.resolveProjectUrl(request.projectUrl()));
    }

    private ProjectResponse toResponse(PlaneProjectDocument project) {
        return new ProjectResponse(project.id().toHexString(), project.planeWorkspaceSlug(), project.planeProjectId(),
                project.planeProjectName(), project.planeProjectIdentifier(), planeService.projectUrl(project.planeWorkspaceSlug(), project.planeProjectId()),
                project.isArchivedInPlane(), project.isAvailable());
    }
}
