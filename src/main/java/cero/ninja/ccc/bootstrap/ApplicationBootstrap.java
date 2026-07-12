package cero.ninja.ccc.bootstrap;

import io.smallrye.config.PropertiesConfigSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Consumer;

/** Coordinates path setup, legacy migration, and Quarkus runtime defaults. */
public final class ApplicationBootstrap {

    private ApplicationBootstrap() {
    }

    public static Result prepare(
            Map<String, String> environment,
            Properties systemProperties,
            Path workingDirectory,
            Path userHome,
            Consumer<String> logger) {
        Objects.requireNonNull(environment);
        Objects.requireNonNull(systemProperties);
        Objects.requireNonNull(workingDirectory);
        Objects.requireNonNull(userHome);
        Objects.requireNonNull(logger);

        ApplicationPaths.Resolved paths = ApplicationPaths.resolve(environment, systemProperties, userHome);
        ApplicationPaths.createDirectories(paths);

        boolean explicitJdbcUrl = systemProperties.containsKey("quarkus.datasource.jdbc.url");
        String configuredJdbcUrl = rawSetting(systemProperties, environment, "QUARKUS_DATASOURCE_JDBC_URL");
        if (configuredJdbcUrl == null) {
            configuredJdbcUrl = stableConfigValue(paths.configFile(), "quarkus.datasource.jdbc.url");
        }
        if (!explicitJdbcUrl && configuredJdbcUrl != null) {
            systemProperties.setProperty("quarkus.datasource.jdbc.url", configuredJdbcUrl);
            explicitJdbcUrl = true;
        }

        Migration migration = Migration.SKIPPED_EXPLICIT_DATABASE;
        if (!explicitJdbcUrl) {
            migration = LegacyDatabaseMigrator.migrate(workingDirectory, paths.database(), logger);
            systemProperties.setProperty("ccc-usage-dashboard.default-jdbc-url",
                    "jdbc:sqlite:" + paths.database() + LegacyDatabaseMigrator.SQLITE_OPTIONS);
        } else {
            logger.accept("Using an explicit quarkus.datasource.jdbc.url; automatic legacy database migration is disabled.");
        }

        systemProperties.putIfAbsent("ccc-usage-dashboard.default-log-file-enabled", "true");
        systemProperties.putIfAbsent("ccc-usage-dashboard.default-log-file-path",
                paths.logDirectory().resolve("ccc-usage-dashboard.log").toString());

        logger.accept("Configuration file: " + paths.configFile());
        logger.accept("Database: " + (explicitJdbcUrl ? "explicit JDBC URL" : paths.database()));
        logger.accept("Logs: " + paths.logDirectory());
        return new Result(
                paths.applicationHome(),
                paths.configFile(),
                paths.dataDirectory(),
                paths.database(),
                paths.logDirectory(),
                migration);
    }

    private static String rawSetting(
            Properties systemProperties, Map<String, String> environment, String environmentName) {
        return systemProperties.containsKey(environmentName)
                ? systemProperties.getProperty(environmentName)
                : environment.get(environmentName);
    }

    private static String stableConfigValue(Path configFile, String property) {
        if (!Files.isRegularFile(configFile)) {
            return null;
        }
        try {
            return new PropertiesConfigSource(configFile.toUri().toURL(), StableApplicationConfigSourceFactory.ORDINAL)
                    .getValue(property);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read ccc-usage-dashboard configuration: " + configFile, e);
        }
    }

    public record Result(
            Path applicationHome,
            Path configFile,
            Path dataDirectory,
            Path database,
            Path logDirectory,
            Migration migration) {
    }

    public enum Migration {
        COPIED,
        DESTINATION_EXISTS,
        NO_LEGACY_DATABASE,
        ALREADY_SELECTED,
        SKIPPED_EXPLICIT_DATABASE
    }
}
