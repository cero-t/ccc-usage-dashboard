package cero.ninja.ccc.store;

import cero.ninja.ccc.db.JdbcClient;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@QuarkusTestResource(value = FreshInstallTest.FreshDatabaseResource.class, restrictToAnnotatedClass = true)
class FreshInstallTest {

    private static final Set<String> EXPECTED_TABLES = Set.of(
            "annotated_events", "cursor", "otel_log_records", "usage_samples");

    @Inject
    JdbcClient db;

    @ConfigProperty(name = "quarkus.application.version")
    String applicationVersion;

    @Test
    void createsTheCompleteSchemaInANewDatabase() {
        Set<String> tables = Set.copyOf(db.sql("""
                        SELECT name
                        FROM sqlite_master
                        WHERE type = 'table' AND name NOT LIKE 'sqlite_%'
                        """)
                .query((rs, row) -> rs.getString(1))
                .list());

        assertEquals(EXPECTED_TABLES, tables);
        assertTrue(hasColumn("annotated_events", "source_tool"));
        assertTrue(hasColumn("annotated_events", "trigger"));
        assertTrue(hasColumn("annotated_events", "cost_usd"));
        assertTrue(indexExists("idx_annotated_events_source_unique"));
        assertTrue(indexExists("idx_annotated_events_claude_agent_prompt"));
        assertTrue(indexExists("idx_otel_log_records_prompt_id"));
    }

    @Test
    void healthReportsThePackagedApplicationVersion() {
        given()
                .when().get("/health")
                .then()
                .statusCode(200)
                .body("status", equalTo("ok"))
                .body("service", equalTo("ccc-usage-dashboard"))
                .body("version", equalTo(applicationVersion));
    }

    private boolean hasColumn(String table, String column) {
        return db.sql("SELECT 1 FROM pragma_table_info(:table) WHERE name = :column")
                .param("table", table)
                .param("column", column)
                .query((rs, row) -> 1)
                .optional()
                .isPresent();
    }

    private boolean indexExists(String index) {
        return db.sql("SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = :index")
                .param("index", index)
                .query((rs, row) -> 1)
                .optional()
                .isPresent();
    }

    public static class FreshDatabaseResource implements QuarkusTestResourceLifecycleManager {

        private Path directory;

        @Override
        public Map<String, String> start() {
            try {
                directory = Files.createTempDirectory("ccc-fresh-install-");
                Path database = directory.resolve("data/ccc-usage-dashboard.sqlite");
                return testConfiguration(database);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to prepare fresh-install test", e);
            }
        }

        @Override
        public void stop() {
            deleteTree(directory);
        }
    }

    static Map<String, String> testConfiguration(Path database) {
        return Map.of(
                "quarkus.datasource.jdbc.url",
                "jdbc:sqlite:" + database + "?journal_mode=WAL&busy_timeout=10000",
                "quarkus.http.test-port", "0",
                "quarkus.grpc.server.test-port", "0",
                "quarkus.scheduler.enabled", "false",
                "quarkus.log.file.enabled", "false",
                "ccc-usage-dashboard.codex.enabled", "false",
                "ccc-usage-dashboard.claude.enabled", "false");
    }

    static void deleteTree(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to delete test path: " + path, e);
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to clean test directory: " + directory, e);
        }
    }
}
