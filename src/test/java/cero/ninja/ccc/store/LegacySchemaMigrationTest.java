package cero.ninja.ccc.store;

import cero.ninja.ccc.db.JdbcClient;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@QuarkusTestResource(value = LegacySchemaMigrationTest.LegacyDatabaseResource.class, restrictToAnnotatedClass = true)
class LegacySchemaMigrationTest {

    @Inject
    JdbcClient db;

    @Inject
    SchemaInitializer schemaInitializer;

    @Test
    void upgradesTheOriginalSchemaWithoutLosingRows() {
        assertEquals(1, countAnnotatedRows());
        assertEquals("codex", stringValue("source_tool"));
        assertEquals(0.1, doubleValue("cost_usd"), 0.000001);
        assertTrue(hasColumn("annotated_events", "request_id"));
        assertTrue(hasColumn("annotated_events", "trigger"));
        assertTrue(hasColumn("annotated_events", "reported_cost_usd"));
        assertTrue(indexExists("idx_annotated_events_source_unique"));
    }

    @Test
    void repeatedSchemaInitializationIsIdempotent() {
        schemaInitializer.init(null);
        schemaInitializer.init(null);

        assertEquals(1, countAnnotatedRows());
        assertEquals("codex", stringValue("source_tool"));
        assertTrue(indexExists("idx_annotated_events_source_unique"));
    }

    private int countAnnotatedRows() {
        return db.sql("SELECT count(*) FROM annotated_events")
                .query((rs, row) -> rs.getInt(1))
                .single();
    }

    private String stringValue(String column) {
        return db.sql("SELECT " + column + " FROM annotated_events WHERE source_log_id = 42")
                .query((rs, row) -> rs.getString(1))
                .single();
    }

    private double doubleValue(String column) {
        return db.sql("SELECT " + column + " FROM annotated_events WHERE source_log_id = 42")
                .query((rs, row) -> rs.getDouble(1))
                .single();
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

    public static class LegacyDatabaseResource implements QuarkusTestResourceLifecycleManager {

        private Path directory;

        @Override
        public Map<String, String> start() {
            try {
                Class.forName("org.sqlite.JDBC");
                directory = Files.createTempDirectory("ccc-legacy-schema-");
                Path database = directory.resolve("data/codex-usage-dashboard.sqlite");
                Files.createDirectories(database.getParent());
                createOriginalSchema(database);
                return FreshInstallTest.testConfiguration(database);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to prepare legacy-schema test", e);
            }
        }

        @Override
        public void stop() {
            FreshInstallTest.deleteTree(directory);
        }

        private void createOriginalSchema(Path database) throws Exception {
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 var statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE otel_log_records (
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          received_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                          record_json TEXT NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE annotated_events (
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          source_log_id INTEGER NOT NULL,
                          annotated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                          time_unix_nano INTEGER,
                          event_name TEXT,
                          thread_id TEXT,
                          model TEXT,
                          input_token_count INTEGER,
                          cached_input_token_count INTEGER,
                          output_token_count INTEGER,
                          error_message TEXT,
                          thread_model TEXT,
                          thread_reasoning_effort TEXT,
                          thread_source TEXT,
                          thread_title TEXT,
                          thread_cwd TEXT,
                          service_tier TEXT,
                          rate_model TEXT,
                          input_credits REAL,
                          cached_credits REAL,
                          output_credits REAL,
                          total_credits REAL,
                          attributes_json TEXT
                        )
                        """);
                statement.execute("""
                        CREATE TABLE usage_samples (
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          sampled_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                          plan_type TEXT,
                          window TEXT NOT NULL,
                          used_percent REAL,
                          remaining_percent REAL,
                          resets_at INTEGER
                        )
                        """);
                statement.execute("CREATE TABLE cursor (name TEXT PRIMARY KEY, value TEXT NOT NULL)");
                statement.execute("""
                        INSERT INTO annotated_events (
                          source_log_id, event_name, total_credits, attributes_json
                        ) VALUES (42, 'api_request', 2.5, '{}')
                        """);
            }
        }
    }
}
