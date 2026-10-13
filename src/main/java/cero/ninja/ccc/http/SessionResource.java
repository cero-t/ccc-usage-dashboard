package cero.ninja.ccc.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.List;

/** Read-only view of allowlisted local collector state. No remote ingestion endpoint. */
@Path("/api/sessions")
public class SessionResource {
    @Inject ObjectMapper mapper;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response sessions() {
        String home = System.getenv("CCC_USAGE_DASHBOARD_HOME");
        java.nio.file.Path root = home == null || home.isBlank()
                ? java.nio.file.Path.of(System.getProperty("user.home"), ".ccc-usage-dashboard")
                : java.nio.file.Path.of(home);
        return readState(root.resolve("sessions/state.json"));
    }

    Response readState(java.nio.file.Path file) {
        try {
            if (!Files.exists(file)) {
                return Response.ok(Map.of("sessions", List.of(), "claudeWindows", List.of(),
                        "collectorStatus", "not-started")).header("Cache-Control", "no-store").build();
            }
            JsonNode state = mapper.readTree(Files.readString(file));
            if (state == null || !state.isObject() || !state.path("sessions").isArray()
                    || !state.path("claudeWindows").isArray()) {
                throw new IOException("Invalid collector state");
            }
            return Response.ok(state).header("Cache-Control", "no-store").build();
        } catch (IOException e) {
            return Response.status(503).entity(Map.of("error", "Session collector state unavailable"))
                    .header("Cache-Control", "no-store").build();
        }
    }
}
