package dev.planetonotion.repositories.projects;

import dev.planetonotion.models.documents.PlaneProjectDocument;
import java.util.List;

public interface PlaneProjectRepositoryCustom {

    void bulkUpsert(List<PlaneProjectDocument> projects);
}
