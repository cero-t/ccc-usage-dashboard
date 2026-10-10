package cero.ninja.ccc.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SessionResourceTest {
    @TempDir Path directory;
    private SessionResource resource() {
        SessionResource resource = new SessionResource();
        resource.mapper = new ObjectMapper();
        return resource;
    }
    @Test void missingCollectorReturnsExplicitEmptyState() {
        try (Response response = resource().readState(directory.resolve("missing.json"))) {
            assertEquals(200, response.getStatus());
            assertEquals("not-started", ((Map<?, ?>) response.getEntity()).get("collectorStatus"));
            assertEquals("no-store", response.getHeaderString("Cache-Control"));
        }
    }
    @Test void invalidStateFailsWithoutExposingFilesystemDetails() throws Exception {
        Path file = directory.resolve("state.json");
        Files.writeString(file, "{\"secret\":\"do not expose\"}");
        try (Response response = resource().readState(file)) {
            assertEquals(503, response.getStatus());
            assertEquals(Map.of("error", "Session collector state unavailable"), response.getEntity());
        }
        Files.writeString(file, "{");
        assertEquals(503, resource().readState(file).getStatus());
    }
    @Test void preservesUnknownContextAndExplicitObservationTimestamp() throws Exception {
        Path file = directory.resolve("state.json");
        Files.writeString(file, "{\"generatedAt\":123,\"sessions\":[{\"contextUsedPercent\":null}],\"claudeWindows\":[]}");
        try (Response response = resource().readState(file)) {
            assertEquals(200, response.getStatus());
            JsonNode state = (JsonNode) response.getEntity();
            assertEquals(123, state.path("generatedAt").asInt());
            assertTrue(state.path("sessions").get(0).path("contextUsedPercent").isNull());
        }
    }
}
