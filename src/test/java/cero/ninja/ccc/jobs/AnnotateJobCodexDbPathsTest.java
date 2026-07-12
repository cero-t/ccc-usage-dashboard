package cero.ninja.ccc.jobs;

import cero.ninja.ccc.db.JdbcClient;
import cero.ninja.ccc.store.Cursors;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(AnnotateJobCodexDbPathsTest.Profile.class)
class AnnotateJobCodexDbPathsTest {

    private static final Path CODEX_DIR = Path.of("target", "codex-db-paths-home");
    private static final Path SQLITE_DIR = CODEX_DIR.resolve("sqlite");
    private static final Path SQLITE_STATE = SQLITE_DIR.resolve("state_5.sqlite");
    private static final Path ROOT_STATE = CODEX_DIR.resolve("state_5.sqlite");
    private static final Path SQLITE_LOGS = SQLITE_DIR.resolve("logs_2.sqlite");
    private static final Path ROOT_LOGS = CODEX_DIR.resolve("logs_2.sqlite");

    @Inject
    JdbcClient db;

    @Inject
    Cursors cursors;

    @Inject
    AnnotateJob annotateJob;

    @BeforeEach
    void resetTables() throws Exception {
        db.sql("DELETE FROM cursor").update();
        db.sql("DELETE FROM annotated_events").update();
        db.sql("DELETE FROM usage_samples").update();
        db.sql("DELETE FROM otel_log_records").update();

        Files.createDirectories(SQLITE_DIR);
        for (Path path : List.of(SQLITE_STATE, ROOT_STATE, SQLITE_LOGS, ROOT_LOGS)) {
            deleteSqliteFiles(path);
        }
        createStateDb(SQLITE_STATE);
        createStateDb(ROOT_STATE);
        createLogsDb(SQLITE_LOGS);
        createLogsDb(ROOT_LOGS);
    }

    @Test
    void readsThreadMetadataFromSqliteStateDb() {
        insertStateThread(SQLITE_STATE, "codex-thread-in-sqlite");
        insertCodexRaw(1, "codex-thread-in-sqlite");

        annotateJob.run();

        assertEquals("user", triggerBySourceLogId(1));
        assertEquals("test-model", modelBySourceLogId(1));
        assertEquals(1, cursors.getLong("annotate_log_id", 0));
    }

    @Test
    void readsThreadMetadataFromRootStateDb() {
        insertStateThread(ROOT_STATE, "codex-thread-in-root");
        insertCodexRaw(1, "codex-thread-in-root");

        annotateJob.run();

        assertEquals("user", triggerBySourceLogId(1));
        assertEquals("test-model", modelBySourceLogId(1));
        assertEquals(1, cursors.getLong("annotate_log_id", 0));
    }

    @Test
    void scansSqliteLogsForAutomatedSignature() {
        insertLog(SQLITE_LOGS, "codex-thread-ambient-sqlite", "captured ambient suggestions payload");
        insertCodexRaw(2, "codex-thread-ambient-sqlite");

        annotateJob.run();

        assertEquals("ambient", triggerBySourceLogId(2));
    }

    @Test
    void scansRootLogsForAutomatedSignature() {
        insertLog(ROOT_LOGS, "codex-thread-ambient", "captured ambient suggestions payload");
        insertCodexRaw(2, "codex-thread-ambient");

        annotateJob.run();

        assertEquals("ambient", triggerBySourceLogId(2));
    }

    private static void deleteSqliteFiles(Path path) throws Exception {
        Files.deleteIfExists(path);
        Files.deleteIfExists(Path.of(path + "-wal"));
        Files.deleteIfExists(Path.of(path + "-shm"));
    }

    private static void createStateDb(Path path) throws Exception {
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + path)) {
            conn.createStatement().execute("""
                    CREATE TABLE threads (
                      id TEXT PRIMARY KEY,
                      model TEXT,
                      reasoning_effort TEXT,
                      source TEXT,
                      title TEXT,
                      cwd TEXT
                    )
                    """);
        }
    }

    private static void createLogsDb(Path path) throws Exception {
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + path)) {
            conn.createStatement().execute("""
                    CREATE TABLE logs (
                      thread_id TEXT,
                      feedback_log_body TEXT
                    )
                    """);
        }
    }

    private static void insertStateThread(Path path, String threadId) {
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + path);
             var ps = conn.prepareStatement("""
                     INSERT INTO threads (id, model, reasoning_effort, source, title, cwd)
                     VALUES (?, 'test-model', 'high', 'desktop', 'test thread', '/tmp')
                     """)) {
            ps.setString(1, threadId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void insertLog(Path path, String threadId, String body) {
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + path);
             var ps = conn.prepareStatement("""
                     INSERT INTO logs (thread_id, feedback_log_body)
                     VALUES (?, ?)
                     """)) {
            ps.setString(1, threadId);
            ps.setString(2, body);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void insertCodexRaw(long id, String threadId) {
        insertRaw(id, """
                {
                  "observed_time_unix_nano": 1781208000000000000,
                  "attributes": {
                    "event.name": "codex.sse_event",
                    "conversation.id": "%s",
                    "input_token_count": 100,
                    "cached_token_count": 10,
                    "output_token_count": 20,
                    "model": "gpt-5.5"
                  },
                  "resource_attributes": {
                    "host.name": "test-host"
                  }
                }
                """.formatted(threadId));
    }

    private void insertRaw(long id, String recordJson) {
        db.sql("""
                INSERT INTO otel_log_records (id, record_json)
                VALUES (:id, :record_json)
                """)
                .param("id", id)
                .param("record_json", recordJson)
                .update();
    }

    private String triggerBySourceLogId(long sourceLogId) {
        return db.sql("""
                SELECT trigger FROM annotated_events
                WHERE source_log_id = :source_log_id
                """)
                .param("source_log_id", sourceLogId)
                .query((rs, row) -> rs.getString(1))
                .single();
    }

    private String modelBySourceLogId(long sourceLogId) {
        return db.sql("""
                SELECT thread_model FROM annotated_events
                WHERE source_log_id = :source_log_id
                """)
                .param("source_log_id", sourceLogId)
                .query((rs, row) -> rs.getString(1))
                .single();
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.datasource.jdbc.url",
                    "jdbc:sqlite:target/annotate-job-codex-db-paths-test.sqlite?journal_mode=WAL&busy_timeout=10000",
                    "quarkus.http.test-port", "0",
                    "quarkus.scheduler.enabled", "false",
                    "ccc-usage-dashboard.codex.enabled", "true",
                    "codex.db.dir", CODEX_DIR.toString(),
                    "codex.open.retries", "0");
        }
    }
}
