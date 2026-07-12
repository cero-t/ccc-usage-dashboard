package cero.ninja.ccc.bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationBootstrapTest {

    @TempDir
    Path temporaryDirectory;

    @BeforeAll
    static void loadSqliteDriver() throws Exception {
        Class.forName("org.sqlite.JDBC");
    }

    @Test
    void usesOneStableHomeOnEveryOperatingSystem() {
        Path userHome = temporaryDirectory.resolve("home");
        Path workingDirectory = temporaryDirectory.resolve("work");
        Properties properties = new Properties();

        ApplicationBootstrap.Result result = prepare(Map.of(), properties, workingDirectory, userHome);

        Path applicationHome = userHome.resolve(".ccc-usage-dashboard").toAbsolutePath();
        assertEquals(applicationHome, result.applicationHome());
        assertEquals(applicationHome.resolve("config/application.properties"), result.configFile());
        assertEquals(applicationHome.resolve("data"), result.dataDirectory());
        assertEquals(applicationHome.resolve("data/ccc-usage-dashboard.sqlite"), result.database());
        assertEquals(applicationHome.resolve("logs"), result.logDirectory());
        assertEquals("jdbc:sqlite:" + result.database() + "?journal_mode=WAL&busy_timeout=10000",
                properties.getProperty("ccc-usage-dashboard.default-jdbc-url"));
        assertEquals(result.logDirectory().resolve("ccc-usage-dashboard.log").toString(),
                properties.getProperty("ccc-usage-dashboard.default-log-file-path"));
    }

    @Test
    void newPathEnvironmentVariablesWinOverLegacyNames() {
        Map<String, String> environment = new HashMap<>();
        environment.put("CCC_USAGE_DASHBOARD_HOME", temporaryDirectory.resolve("new-home").toString());
        environment.put("CODEX_USAGE_DASHBOARD_HOME", temporaryDirectory.resolve("legacy-home").toString());
        environment.put("CCC_USAGE_DASHBOARD_CONFIG_FILE", temporaryDirectory.resolve("new-config.properties").toString());
        environment.put("CODEX_USAGE_DASHBOARD_CONFIG_FILE", temporaryDirectory.resolve("legacy-config.properties").toString());
        environment.put("CCC_USAGE_DASHBOARD_DATA_DIR", temporaryDirectory.resolve("new-data").toString());
        environment.put("CODEX_USAGE_DASHBOARD_DATA_DIR", temporaryDirectory.resolve("legacy-data").toString());
        environment.put("CCC_USAGE_DASHBOARD_DATABASE_PATH", temporaryDirectory.resolve("new-db.sqlite").toString());
        environment.put("CODEX_USAGE_DASHBOARD_DATABASE_PATH", temporaryDirectory.resolve("legacy-db.sqlite").toString());
        environment.put("CCC_USAGE_DASHBOARD_LOG_DIR", temporaryDirectory.resolve("new-logs").toString());
        environment.put("CODEX_USAGE_DASHBOARD_LOG_DIR", temporaryDirectory.resolve("legacy-logs").toString());

        ApplicationBootstrap.Result result = prepare(
                environment, new Properties(), temporaryDirectory.resolve("work"), temporaryDirectory.resolve("home"));

        assertEquals(temporaryDirectory.resolve("new-home").toAbsolutePath(), result.applicationHome());
        assertEquals(temporaryDirectory.resolve("new-config.properties").toAbsolutePath(), result.configFile());
        assertEquals(temporaryDirectory.resolve("new-data").toAbsolutePath(), result.dataDirectory());
        assertEquals(temporaryDirectory.resolve("new-db.sqlite").toAbsolutePath(), result.database());
        assertEquals(temporaryDirectory.resolve("new-logs").toAbsolutePath(), result.logDirectory());
    }

    @Test
    void legacyPathEnvironmentVariablesRemainSupported() {
        Path legacyHome = temporaryDirectory.resolve("legacy-home");
        Path legacyConfig = temporaryDirectory.resolve("legacy-config.properties");
        Path legacyData = temporaryDirectory.resolve("legacy-data");
        Path legacyDatabase = temporaryDirectory.resolve("legacy-db.sqlite");
        Path legacyLogs = temporaryDirectory.resolve("legacy-logs");
        Map<String, String> environment = Map.of(
                "CODEX_USAGE_DASHBOARD_HOME", legacyHome.toString(),
                "CODEX_USAGE_DASHBOARD_CONFIG_FILE", legacyConfig.toString(),
                "CODEX_USAGE_DASHBOARD_DATA_DIR", legacyData.toString(),
                "CODEX_USAGE_DASHBOARD_DATABASE_PATH", legacyDatabase.toString(),
                "CODEX_USAGE_DASHBOARD_LOG_DIR", legacyLogs.toString());

        ApplicationBootstrap.Result result = prepare(
                environment, new Properties(), temporaryDirectory.resolve("work"), temporaryDirectory.resolve("home"));

        assertEquals(legacyHome.toAbsolutePath(), result.applicationHome());
        assertEquals(legacyConfig.toAbsolutePath(), result.configFile());
        assertEquals(legacyData.toAbsolutePath(), result.dataDirectory());
        assertEquals(legacyDatabase.toAbsolutePath(), result.database());
        assertEquals(legacyLogs.toAbsolutePath(), result.logDirectory());
    }

    @Test
    void reportsTheStableApplicationPropertiesPath() {
        Path userHome = temporaryDirectory.resolve("home");
        Path workingDirectory = temporaryDirectory.resolve("work");
        ApplicationBootstrap.Result result = prepare(Map.of(), new Properties(), workingDirectory, userHome);

        assertEquals(userHome.resolve(".ccc-usage-dashboard/config/application.properties").toAbsolutePath(),
                result.configFile());
        assertTrue(Files.isDirectory(result.configFile().getParent()));
    }

    @Test
    void explicitJdbcUrlDisablesAutomaticMigration() throws Exception {
        Path workingDirectory = temporaryDirectory.resolve("work");
        Path legacy = workingDirectory.resolve("data/codex-usage-dashboard.sqlite");
        createDatabase(legacy, "legacy");
        Properties properties = new Properties();
        Map<String, String> environment = Map.of(
                "QUARKUS_DATASOURCE_JDBC_URL", "jdbc:sqlite:/tmp/selected.sqlite");

        ApplicationBootstrap.Result result = prepare(
                environment, properties, workingDirectory, temporaryDirectory.resolve("home"));

        assertEquals(ApplicationBootstrap.Migration.SKIPPED_EXPLICIT_DATABASE, result.migration());
        assertEquals("jdbc:sqlite:/tmp/selected.sqlite", properties.getProperty("quarkus.datasource.jdbc.url"));
        assertTrue(Files.exists(legacy));
        assertFalse(Files.exists(result.database()));
    }

    @Test
    void systemPropertyDatasourceUrlWinsOverEnvironmentAndStableConfiguration() throws Exception {
        Path workingDirectory = temporaryDirectory.resolve("work-with-all-datasource-settings");
        Path applicationHome = temporaryDirectory.resolve("app-home");
        Path configFile = applicationHome.resolve("config/application.properties");
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, "quarkus.datasource.jdbc.url=jdbc:sqlite:/tmp/from-config.sqlite\n");

        Properties properties = new Properties();
        properties.setProperty("quarkus.datasource.jdbc.url", "jdbc:sqlite:/tmp/from-system-property.sqlite");
        Map<String, String> environment = Map.of(
                "CCC_USAGE_DASHBOARD_HOME", applicationHome.toString(),
                "QUARKUS_DATASOURCE_JDBC_URL", "jdbc:sqlite:/tmp/from-environment.sqlite");

        ApplicationBootstrap.Result result = prepare(
                environment, properties, workingDirectory, temporaryDirectory.resolve("home"));

        assertEquals(ApplicationBootstrap.Migration.SKIPPED_EXPLICIT_DATABASE, result.migration());
        assertEquals("jdbc:sqlite:/tmp/from-system-property.sqlite",
                properties.getProperty("quarkus.datasource.jdbc.url"));
    }

    @Test
    void stableApplicationPropertiesCanSelectTheDatabase() throws Exception {
        Path workingDirectory = temporaryDirectory.resolve("work-with-config");
        Path userHome = temporaryDirectory.resolve("home-with-config");
        Path legacy = workingDirectory.resolve("data/codex-usage-dashboard.sqlite");
        Path configuredDatabase = temporaryDirectory.resolve("configured.sqlite");
        Path configFile = userHome.resolve(".ccc-usage-dashboard/config/application.properties");
        createDatabase(legacy, "legacy");
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, "quarkus.datasource.jdbc.url=jdbc:sqlite:" + configuredDatabase + "\n");
        Properties properties = new Properties();

        ApplicationBootstrap.Result result = prepare(Map.of(), properties, workingDirectory, userHome);

        assertEquals(ApplicationBootstrap.Migration.SKIPPED_EXPLICIT_DATABASE, result.migration());
        assertEquals("jdbc:sqlite:" + configuredDatabase, properties.getProperty("quarkus.datasource.jdbc.url"));
        assertTrue(Files.exists(legacy));
        assertFalse(Files.exists(result.database()));
    }

    @Test
    void copiesLegacyDatabaseOnceAndNeverOverwritesDestination() throws Exception {
        Path workingDirectory = temporaryDirectory.resolve("work");
        Path userHome = temporaryDirectory.resolve("home");
        Path legacy = workingDirectory.resolve("data/codex-usage-dashboard.sqlite");
        createDatabase(legacy, "legacy");

        ApplicationBootstrap.Result first = prepare(Map.of(), new Properties(), workingDirectory, userHome);

        assertEquals(ApplicationBootstrap.Migration.COPIED, first.migration());
        assertEquals("legacy", readValue(first.database()));
        assertEquals("legacy", readValue(legacy));

        replaceValue(first.database(), "destination");
        ApplicationBootstrap.Result second = prepare(Map.of(), new Properties(), workingDirectory, userHome);

        assertEquals(ApplicationBootstrap.Migration.DESTINATION_EXISTS, second.migration());
        assertEquals("destination", readValue(second.database()));
        assertEquals("legacy", readValue(legacy));
    }

    @Test
    void homeAndDatabasePathsCanBeOverridden() {
        Path userHome = temporaryDirectory.resolve("home");
        Path selectedHome = temporaryDirectory.resolve("selected-home");
        Path selectedDatabase = temporaryDirectory.resolve("selected-data/custom.sqlite");
        Map<String, String> environment = Map.of(
                "CCC_USAGE_DASHBOARD_HOME", selectedHome.toString(),
                "CCC_USAGE_DASHBOARD_DATABASE_PATH", selectedDatabase.toString());

        ApplicationBootstrap.Result result = prepare(
                environment, new Properties(), temporaryDirectory.resolve("work"), userHome);

        assertEquals(selectedHome.toAbsolutePath(), result.applicationHome());
        assertEquals(selectedDatabase.toAbsolutePath(), result.database());
        assertTrue(Files.isDirectory(selectedDatabase.getParent()));
    }

    @Test
    void rejectsAStateDirectoryThatIsAnExistingFile() throws Exception {
        Path invalidLogDirectory = temporaryDirectory.resolve("not-a-directory");
        Files.writeString(invalidLogDirectory, "occupied");

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> prepare(
                Map.of("CCC_USAGE_DASHBOARD_LOG_DIR", invalidLogDirectory.toString()),
                new Properties(),
                temporaryDirectory.resolve("work"),
                temporaryDirectory.resolve("home")));

        assertTrue(failure.getMessage().contains("Failed to create ccc-usage-dashboard application directories"));
    }

    private ApplicationBootstrap.Result prepare(
            Map<String, String> environment,
            Properties properties,
            Path workingDirectory,
            Path userHome) {
        return ApplicationBootstrap.prepare(
                environment, properties, workingDirectory, userHome, new ArrayList<String>()::add);
    }

    private void createDatabase(Path path, String value) throws Exception {
        Files.createDirectories(path.getParent());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE marker (value TEXT NOT NULL)");
            statement.execute("INSERT INTO marker(value) VALUES ('" + value + "')");
        }
    }

    private void replaceValue(Path path, String value) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             var statement = connection.createStatement()) {
            statement.execute("UPDATE marker SET value = '" + value + "'");
        }
    }

    private String readValue(Path path) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT value FROM marker")) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }
}
