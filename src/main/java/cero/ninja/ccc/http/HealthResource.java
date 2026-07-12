package cero.ninja.ccc.http;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;

@Path("/health")
public class HealthResource {

    @ConfigProperty(name = "quarkus.application.version")
    String applicationVersion;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "service", "ccc-usage-dashboard",
                "version", applicationVersion
        );
    }
}
