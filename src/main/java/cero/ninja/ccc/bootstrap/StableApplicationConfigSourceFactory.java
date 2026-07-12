package cero.ninja.ccc.bootstrap;

import io.smallrye.config.ConfigSourceContext;
import io.smallrye.config.ConfigSourceFactory;
import io.smallrye.config.PropertiesConfigSource;
import org.eclipse.microprofile.config.spi.ConfigSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Loads the optional per-user application.properties with normal Quarkus semantics. */
public final class StableApplicationConfigSourceFactory implements ConfigSourceFactory {

    static final int ORDINAL = 290;

    @Override
    public Iterable<ConfigSource> getConfigSources(ConfigSourceContext context) {
        Path userHome = Path.of(System.getProperty("user.home"));
        Path applicationHome = ApplicationPaths.resolveApplicationHome(
                System.getenv(), System.getProperties(), userHome);
        Path configFile = ApplicationPaths.resolveConfigFile(
                System.getenv(), System.getProperties(), applicationHome, userHome);
        if (!Files.isRegularFile(configFile)) {
            return List.of();
        }
        try {
            return List.of(new PropertiesConfigSource(configFile.toUri().toURL(), ORDINAL));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load ccc-usage-dashboard configuration: " + configFile, e);
        }
    }
}
