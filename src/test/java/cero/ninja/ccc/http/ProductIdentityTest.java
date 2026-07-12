package cero.ninja.ccc.http;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductIdentityTest {

    private static final String APPLICATION_NAME = "ccc-usage-dashboard";
    private static final String DESCRIPTION = "CCC (Codex and Claude Code) Usage Dashboard";

    @Test
    void exposesThePublicProductIdentity() throws IOException {
        String page = resource("/META-INF/resources/index.html");

        assertTrue(page.contains("<title>" + APPLICATION_NAME + " — " + DESCRIPTION + "</title>"));
        assertTrue(page.contains(">" + APPLICATION_NAME + "</h1>"));
        assertTrue(page.contains("<span class=\"sub\">" + DESCRIPTION + "</span>"));
        assertEquals(Map.of("status", "ok", "service", "ccc-usage-dashboard"),
                new HealthResource().health());
    }

    @Test
    void usesTheNewArtifactNameAndStablePathBootstrap() throws IOException {
        String properties = resource("/application.properties");

        assertTrue(properties.contains("quarkus.package.output-name=ccc-usage-dashboard"));
        assertTrue(properties.contains("quarkus.package.main-class=cero.ninja.ccc.bootstrap.CccUsageDashboardMain"));
        assertFalse(properties.contains("jdbc:sqlite:data/codex-usage-dashboard.sqlite"));
        assertTrue(properties.contains("ccc-usage-dashboard.codex.enabled="));
    }

    private String resource(String path) throws IOException {
        try (InputStream input = ProductIdentityTest.class.getResourceAsStream(path)) {
            assertNotNull(input, "missing test resource: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
