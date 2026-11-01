package dev.planetonotion.services;

import tools.jackson.databind.JsonNode;
import dev.planetonotion.integrations.PlaneClient;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

@Service
public class PlaneService {

    private final PlaneClient planeClient;

    public PlaneService(PlaneClient planeClient) {
        this.planeClient = planeClient;
    }

    public void testConnection() {
        planeClient.testConnection();
    }

    public void forEachProjectPage(String workspaceSlug, Consumer<List<JsonNode>> pageConsumer) {
        planeClient.forEachProjectPage(workspaceSlug, pageConsumer);
    }

    public JsonNode getProject(String workspaceSlug, String projectId) {
        return planeClient.getProject(workspaceSlug, projectId);
    }

    public void forEachProjectWorkItemPage(String workspaceSlug, String projectId, Instant updatedAtFrom,
            Instant updatedAtTo, Consumer<List<JsonNode>> pageConsumer) {
        planeClient.forEachProjectWorkItemPage(workspaceSlug, projectId, updatedAtFrom, updatedAtTo, pageConsumer);
    }

    public JsonNode getWorkItemByIdentifier(String workspaceSlug, String identifier) {
        return planeClient.getWorkItemByIdentifier(workspaceSlug, identifier);
    }

    public JsonNode getWorkItem(String workspaceSlug, String projectId, String workItemId) {
        return planeClient.getWorkItem(workspaceSlug, projectId, workItemId);
    }

    public String projectUrl(String workspaceSlug, String projectId) {
        return planeClient.projectUrl(workspaceSlug, projectId);
    }

    public String workItemUrl(String workspaceSlug, String projectId, String workItemId) {
        return planeClient.workItemUrl(workspaceSlug, projectId, workItemId);
    }

    public List<JsonNode> listWorkItemStates(String workspaceSlug, String projectId) {
        return planeClient.listWorkItemStates(workspaceSlug, projectId);
    }

    public List<JsonNode> listWorkItemLabels(String workspaceSlug, String projectId) {
        return planeClient.listWorkItemLabels(workspaceSlug, projectId);
    }
}
