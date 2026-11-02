package dev.planetonotion.models.documents;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

class PlaneProjectDocumentTest {

    @Test
    void destinationIds_mustBeAllPresentOrAllAbsent() {
        Instant now = Instant.now();

        assertThatThrownBy(() -> new PlaneProjectDocument(new ObjectId(), "workspace", "project", "Project",
                "PROJ", false, true, "page-id", null, null, now, now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("all present or all absent");
    }
}
