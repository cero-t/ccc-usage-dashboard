package cero.ninja.ccc.bootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Resolves and creates the stable per-user filesystem layout. */
final class ApplicationPaths {

    private static final String DEFAULT_DIRECTORY = ".ccc-usage-dashboard";
    private static final String DATABASE_FILE = "ccc-usage-dashboard.sqlite";

    private ApplicationPaths() {
    }

    static Resolved resolve(Map<String, String> environment, Properties systemProperties, Path userHome) {
        Path applicationHome = resolveApplicationHome(environment, systemProperties, userHome);
        Path configFile = resolveConfigFile(environment, systemProperties, applicationHome, userHome);
        Path dataDirectory = pathSetting(
                environment,
                systemProperties,
                "ccc-usage-dashboard.data-dir",
                "CCC_USAGE_DASHBOARD_DATA_DIR",
                "CODEX_USAGE_DASHBOARD_DATA_DIR",
                applicationHome.resolve("data"),
                userHome);
        Path logDirectory = pathSetting(
                environment,
                systemProperties,
                "ccc-usage-dashboard.log-dir",
                "CCC_USAGE_DASHBOARD_LOG_DIR",
                "CODEX_USAGE_DASHBOARD_LOG_DIR",
                applicationHome.resolve("logs"),
                userHome);
        Path database = pathSetting(
                environment,
                systemProperties,
                "ccc-usage-dashboard.database-path",
                "CCC_USAGE_DASHBOARD_DATABASE_PATH",
                "CODEX_USAGE_DASHBOARD_DATABASE_PATH",
                dataDirectory.resolve(DATABASE_FILE),
                userHome);
        return new Resolved(applicationHome, configFile, dataDirectory, database, logDirectory);
    }

    static Path resolveApplicationHome(
            Map<String, String> environment, Properties systemProperties, Path userHome) {
        return pathSetting(
                environment,
                systemProperties,
                "ccc-usage-dashboard.home",
                "CCC_USAGE_DASHBOARD_HOME",
                "CODEX_USAGE_DASHBOARD_HOME",
                userHome.resolve(DEFAULT_DIRECTORY),
                userHome);
    }

    static Path resolveConfigFile(
            Map<String, String> environment,
            Properties systemProperties,
            Path applicationHome,
            Path userHome) {
        return pathSetting(
                environment,
                systemProperties,
                "ccc-usage-dashboard.config-file",
                "CCC_USAGE_DASHBOARD_CONFIG_FILE",
                "CODEX_USAGE_DASHBOARD_CONFIG_FILE",
                applicationHome.resolve("config").resolve("application.properties"),
                userHome);
    }

    static void createDirectories(Resolved paths) {
        List<Path> directories = new ArrayList<>();
        addUnique(directories, paths.applicationHome());
        addUnique(directories, paths.configFile().getParent());
        addUnique(directories, paths.dataDirectory());
        addUnique(directories, paths.logDirectory());
        addUnique(directories, paths.database().getParent());
        try {
            for (Path directory : directories) {
                Files.createDirectories(directory);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create ccc-usage-dashboard application directories", e);
        }
    }

    private static void addUnique(List<Path> directories, Path directory) {
        if (directory != null && !directories.contains(directory)) {
            directories.add(directory);
        }
    }

    private static Path pathSetting(
            Map<String, String> environment,
            Properties systemProperties,
            String property,
            String newEnvironment,
            String legacyEnvironment,
            Path defaultValue,
            Path userHome) {
        String value = systemProperties.getProperty(property);
        if (value == null) {
            value = aliasedValue(systemProperties, environment, newEnvironment, legacyEnvironment);
        }
        if (value == null || value.isBlank()) {
            return defaultValue.toAbsolutePath().normalize();
        }
        return Path.of(expandHome(value.trim(), userHome)).toAbsolutePath().normalize();
    }

    private static String aliasedValue(
            Properties systemProperties,
            Map<String, String> environment,
            String newEnvironment,
            String legacyEnvironment) {
        if (systemProperties.containsKey(newEnvironment)) {
            return systemProperties.getProperty(newEnvironment);
        }
        if (systemProperties.containsKey(legacyEnvironment)) {
            return systemProperties.getProperty(legacyEnvironment);
        }
        if (environment.containsKey(newEnvironment)) {
            return environment.get(newEnvironment);
        }
        return environment.get(legacyEnvironment);
    }

    private static String expandHome(String value, Path userHome) {
        if (value.equals("~") || value.equals("$HOME") || value.equals("${HOME}")) {
            return userHome.toString();
        }
        if (value.startsWith("~/")) {
            return userHome.resolve(value.substring(2)).toString();
        }
        if (value.startsWith("$HOME/")) {
            return userHome.resolve(value.substring(6)).toString();
        }
        if (value.startsWith("${HOME}/")) {
            return userHome.resolve(value.substring(8)).toString();
        }
        return value;
    }

    record Resolved(
            Path applicationHome,
            Path configFile,
            Path dataDirectory,
            Path database,
            Path logDirectory) {
    }
}
