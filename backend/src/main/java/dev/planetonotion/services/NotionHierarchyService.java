package dev.planetonotion.services;

import dev.planetonotion.common.exceptions.IntegrationException;
import dev.planetonotion.common.IntegrationProperties;
import dev.planetonotion.common.PlaneToNotionEnums.ConfigStatus;
import dev.planetonotion.integrations.NotionBlockClient;
import dev.planetonotion.integrations.NotionClient;
import dev.planetonotion.models.documents.configs.SyncConfigDocument;
import dev.planetonotion.models.documents.PlaneProjectDocument;
import dev.planetonotion.models.documents.PlaneWorkspaceDocument;
import dev.planetonotion.repositories.MongoQueryOptions;
import dev.planetonotion.repositories.PlaneProjectRepository;
import dev.planetonotion.repositories.PlaneWorkspaceRepository;
import dev.planetonotion.repositories.SyncConfigRepository;
import dev.planetonotion.repositories.SyncMappingRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.Function;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Creates and updates the Notion workspace hierarchy and its sync summaries.
 */
@Service
public class NotionHierarchyService {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotionHierarchyService.class);

    private final PlaneWorkspaceRepository workspaceRepository;
    private final PlaneProjectRepository projectRepository;
    private final SyncConfigRepository configRepository;
    private final SyncMappingRepository mappingRepository;
    private final NotionClient notionClient;
    private final NotionBlockClient notionBlockClient;
    private final IntegrationProperties integrationProperties;

    /** Creates the service with storage and Notion clients. */
    public NotionHierarchyService(PlaneWorkspaceRepository workspaceRepository,
            PlaneProjectRepository projectRepository,
            SyncConfigRepository configRepository, SyncMappingRepository mappingRepository, NotionClient notionClient,
            NotionBlockClient notionBlockClient, IntegrationProperties integrationProperties) {
        this.workspaceRepository = workspaceRepository;
        this.projectRepository = projectRepository;
        this.configRepository = configRepository;
        this.mappingRepository = mappingRepository;
        this.notionClient = notionClient;
        this.notionBlockClient = notionBlockClient;
        this.integrationProperties = integrationProperties;
    }

    /**
     * Returns the stored Notion page for a Plane workspace, creating and saving
     * one when it does not exist.
     *
     * @param workspaceSlug Plane workspace slug
     * @return persisted workspace page reference
     */
    public synchronized PlaneWorkspaceDocument ensureWorkspacePage(String workspaceSlug) {
        // Reuse stored workspace page when available.
        PlaneWorkspaceDocument existing = workspaceRepository.findByPlaneWorkspaceSlug(workspaceSlug).orElse(null);
        if (existing != null)
            return existing;

        // Create page and persist its identifier.
        String pageId = notionClient.createWorkspacePage(workspaceSlug);
        Instant now = Instant.now();
        // Save reference and clean up failures.
        try {
            return workspaceRepository.save(new PlaneWorkspaceDocument(null, workspaceSlug, pageId, now, now));
        } catch (DuplicateKeyException exception) {
            notionBlockClient.archivePage(pageId);
            return workspaceRepository.findByPlaneWorkspaceSlug(workspaceSlug).orElseThrow(() -> exception);
        } catch (RuntimeException exception) {
            notionBlockClient.archivePage(pageId);
            throw exception;
        }
    }

    /**
     * Updates a project's Notion page title and workspace parent from current
     * Plane project details.
     *
     * @param project catalog project with its Notion page ID
     */
    public void synchronizeProjectPage(PlaneProjectDocument project) {
        // Ensure workspace page before project update.
        PlaneWorkspaceDocument workspace = ensureWorkspacePage(project.planeWorkspaceSlug());
        notionClient.synchronizeProjectPage(project.notionProjectPageId(), project.planeProjectName(),
                workspace.notionPageId());
    }

    /** Refreshes hierarchy summaries in Notion without failing the caller. */
    public void refreshSummaries() {
        // Treat summary refresh failures as nonfatal.
        try {
            writeSummaries();
        } catch (IntegrationException exception) {
            LOGGER.warn("Could not refresh Notion sync summaries ({}; HTTP {})",
                    exception.errorCode(), exception.upstreamStatus());
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not refresh Notion sync summaries ({})", exception.getClass().getSimpleName());
        }
    }

    /** Rebuilds workspace and parent-page summaries from stored sync data. */
    private void writeSummaries() {
        // Load configs and project destinations.
        List<SyncConfigDocument> configs = configRepository.findAll(MongoQueryOptions.empty());
        if (configs.isEmpty())
            return;

        List<ObjectId> projectRefs = configs.stream().map(SyncConfigDocument::planeProjectDocumentId).distinct()
                .toList();
        Map<ObjectId, PlaneProjectDocument> projectsById = projectRepository.findAllById(projectRefs).stream()
                .collect(Collectors.toMap(PlaneProjectDocument::id, Function.identity()));
        List<String> dataSourceIds = projectsById.values().stream().map(PlaneProjectDocument::notionDataSourceId)
                .filter(id -> id != null && !id.isBlank()).distinct().toList();
        // Count rows and group configured projects.
        Map<String, Long> mappingCounts = mappingRepository.countByNotionDataSourceIds(dataSourceIds);
        Map<String, List<ProjectSummary>> projectsByWorkspace = new LinkedHashMap<>();
        for (SyncConfigDocument config : configs) {
            PlaneProjectDocument project = projectsById.get(config.planeProjectDocumentId());
            if (project == null || project.notionDataSourceId() == null || project.notionDataSourceId().isBlank())
                continue;
            projectsByWorkspace.computeIfAbsent(project.planeWorkspaceSlug(), ignored -> new ArrayList<>())
                    .add(new ProjectSummary(project, config,
                            mappingCounts.getOrDefault(project.notionDataSourceId(), 0L)));
        }

        // Write one overview per workspace.
        List<WorkspaceSummary> workspaceSummaries = new ArrayList<>();
        for (Map.Entry<String, List<ProjectSummary>> entry : projectsByWorkspace.entrySet()) {
            List<ProjectSummary> projects = entry.getValue().stream()
                    .sorted(Comparator.comparing(summary -> summary.project().planeProjectName(),
                            String.CASE_INSENSITIVE_ORDER))
                    .toList();
            Counts counts = counts(projects);
            long workItemCount = projects.stream().mapToLong(ProjectSummary::workItemCount).sum();
            PlaneWorkspaceDocument workspace = ensureWorkspacePage(entry.getKey());
            String summary = projects.size() + " configured " + plural(projects.size(), "project")
                    + "  ·  " + workItemCount + " synced " + plural(workItemCount, "work item");
            List<List<String>> projectRows = projects.stream().map(project -> List.of(
                    project.project().planeProjectName(), project.project().planeProjectIdentifier(),
                    project.config().status().name(),
                    Long.toString(project.workItemCount()))).toList();
            notionBlockClient.upsertPageTableSummary(workspace.notionPageId(), "Project overview",
                    List.of("Projects synced from Plane"), "Workspace sync summary:", summary,
                    List.of("Project", "Key", "Sync status", "Work items"), projectRows);
            workspaceSummaries.add(new WorkspaceSummary(entry.getKey(), projects.size(), counts, workItemCount));
        }

        // Roll up workspace totals for parent.
        long totalProjects = workspaceSummaries.stream().mapToLong(WorkspaceSummary::projectCount).sum();
        long totalWorkItems = workspaceSummaries.stream().mapToLong(WorkspaceSummary::workItemCount).sum();
        String parentSummary = workspaceSummaries.size() + " " + plural(workspaceSummaries.size(), "workspace")
                + "  ·  " + totalProjects + " configured " + plural(totalProjects, "project")
                + "  ·  " + totalWorkItems + " synced " + plural(totalWorkItems, "work item");
        List<List<String>> workspaceRows = workspaceSummaries.stream()
                .sorted(Comparator.comparing(WorkspaceSummary::workspaceSlug, String.CASE_INSENSITIVE_ORDER))
                .map(summary -> List.of(summary.workspaceSlug(), Long.toString(summary.projectCount()),
                        statusBreakdown(summary.counts()), Long.toString(summary.workItemCount())))
                .toList();
        notionBlockClient.upsertPageTableSummary(integrationProperties.notionParentPageId(), "Workspace overview",
                List.of("Plane workspace rollup"), "Plane sync summary:", parentSummary,
                List.of("Workspace", "Projects", "Project status", "Synced work items"), workspaceRows);
    }

    /** Formats active, disabled, and archived project totals. */
    private String statusBreakdown(Counts counts) {
        return counts.active() + " active · " + counts.disabled() + " disabled · " + counts.archived() + " archived";
    }

    /** Adds the plural suffix unless the count is one. */
    private String plural(long count, String singular) {
        return count == 1 ? singular : singular + "s";
    }

    /** Counts configured projects by their sync status. */
    private Counts counts(List<ProjectSummary> projects) {
        return new Counts(
                (int) projects.stream().filter(summary -> summary.config().status() == ConfigStatus.ACTIVE).count(),
                (int) projects.stream().filter(summary -> summary.config().status() == ConfigStatus.DISABLED).count(),
                (int) projects.stream().filter(summary -> summary.config().status() == ConfigStatus.ARCHIVED).count());
    }

    /** Workspace overview row data for one configured project. */
    private record ProjectSummary(PlaneProjectDocument project, SyncConfigDocument config, long workItemCount) {
    }

    /** Aggregated project and work-item counts for one workspace. */
    private record WorkspaceSummary(String workspaceSlug, long projectCount, Counts counts, long workItemCount) {
    }

    /** Config status totals displayed in the hierarchy summaries. */
    private record Counts(int active, int disabled, int archived) {
    }
}
