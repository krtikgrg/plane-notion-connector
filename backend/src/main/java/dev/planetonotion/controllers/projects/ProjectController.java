package dev.planetonotion.controllers.projects;

import dev.planetonotion.integrations.plane.PlaneClient;
import dev.planetonotion.models.documents.PlaneProjectDocument;
import dev.planetonotion.models.dto.ApiDtos.PlaneConnectionResponse;
import dev.planetonotion.models.dto.ApiDtos.ProjectRefreshRequest;
import dev.planetonotion.models.dto.ApiDtos.ProjectRefreshResponse;
import dev.planetonotion.models.dto.ApiDtos.ProjectResponse;
import dev.planetonotion.models.dto.ApiDtos.ProjectUrlRequest;
import dev.planetonotion.services.projects.ProjectCatalogService;
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

    private final PlaneClient planeClient;
    private final ProjectCatalogService projectCatalogService;

    public ProjectController(PlaneClient planeClient, ProjectCatalogService projectCatalogService) {
        this.planeClient = planeClient;
        this.projectCatalogService = projectCatalogService;
    }

    @PostMapping("/connection/test")
    public PlaneConnectionResponse testConnection() {
        planeClient.testConnection();
        return new PlaneConnectionResponse("CONNECTED");
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
        return new ProjectResponse(project.id().toHexString(), project.workspaceSlug(), project.projectId(),
                project.name(), project.identifier(), planeClient.projectUrl(project.workspaceSlug(), project.projectId()),
                project.isArchived(), project.isAvailable(), project.lastRefreshedAt());
    }
}
