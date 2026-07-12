package cero.ninja.ccc.bootstrap;

import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ConfigurationCompatibilityTest {

    @Test
    void legacyEnvironmentVariablesRemainConfigurationFallbacks() throws Exception {
        SmallRyeConfig config = config(Map.of(
                "CODEX_USAGE_DASHBOARD_CODEX_ENABLED", "false",
                "CODEX_USAGE_DASHBOARD_ANNOTATE_EVERY", "17s"));

        assertEquals(false, config.getValue("ccc-usage-dashboard.codex.enabled", Boolean.class));
        assertEquals("17s", config.getValue("ccc-usage-dashboard.annotate.every", String.class));
    }

    @Test
    void newEnvironmentVariablesOverrideLegacyNames() throws Exception {
        SmallRyeConfig config = config(Map.of(
                "CCC_USAGE_DASHBOARD_CODEX_ENABLED", "true",
                "CODEX_USAGE_DASHBOARD_CODEX_ENABLED", "false",
                "CCC_USAGE_DASHBOARD_ANNOTATE_EVERY", "3s",
                "CODEX_USAGE_DASHBOARD_ANNOTATE_EVERY", "17s"));

        assertEquals(true, config.getValue("ccc-usage-dashboard.codex.enabled", Boolean.class));
        assertEquals("3s", config.getValue("ccc-usage-dashboard.annotate.every", String.class));
    }

    private SmallRyeConfig config(Map<String, String> environment) throws Exception {
        URL properties = ConfigurationCompatibilityTest.class.getResource("/application.properties");
        assertNotNull(properties);
        return new SmallRyeConfigBuilder()
                .withSources(
                        new PropertiesConfigSource(properties, 250),
                        new EnvConfigSource(environment, 300))
                .addDefaultInterceptors()
                .build();
    }
}
