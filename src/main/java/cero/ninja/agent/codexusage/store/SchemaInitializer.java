package cero.ninja.agent.codexusage.store;

import cero.ninja.agent.codexusage.db.JdbcClient;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates every table the app owns, on the local {@code codex-usage-dashboard.sqlite}.
 *
 * <p>The pipeline is append-only and split into three writers that all target
 * this one file:
 * <ul>
 *   <li>{@code otel_log_records} — raw OTLP log records, stored verbatim as JSON
 *       by the receive path with no parsing or filtering;</li>
 *   <li>{@code annotated_events} — rows derived by the annotate job (parse raw +
 *       enrich from Codex's own DBs);</li>
 *   <li>{@code usage_samples} — periodic Codex rate-limit snapshots;</li>
 *   <li>{@code cursor} — forward-only cursors so each job resumes where it left
 *       off without gaps or double counting.</li>
 * </ul>
 */
@ApplicationScoped
public class SchemaInitializer {

    private static final String RAW_TABLE = """
            CREATE TABLE IF NOT EXISTS otel_log_records (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              received_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
              record_json TEXT NOT NULL
            )
            """;

    private static final String ANNOTATED_TABLE = """
            CREATE TABLE IF NOT EXISTS annotated_events (
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
            """;

    private static final String ANNOTATED_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_annotated_events_source
            ON annotated_events(source_log_id)
            """;

    private static final String ANNOTATED_EVENT_TIME_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_annotated_events_event_epoch
            ON annotated_events (
              CAST(COALESCE(NULLIF(time_unix_nano, 0) / 1000000000, strftime('%s', annotated_at)) AS INTEGER)
            )
            """;

    private static final String ANNOTATED_CREDIT_TIME_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_annotated_events_credit_epoch
            ON annotated_events (
              CAST(COALESCE(NULLIF(time_unix_nano, 0) / 1000000000, strftime('%s', annotated_at)) AS INTEGER)
            )
            WHERE total_credits IS NOT NULL
            """;

    private static final String USAGE_TABLE = """
            CREATE TABLE IF NOT EXISTS usage_samples (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              sampled_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
              plan_type TEXT,
              window TEXT NOT NULL,
              used_percent REAL,
              remaining_percent REAL,
              resets_at INTEGER
            )
            """;

    private static final String USAGE_WINDOW_TIME_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_usage_samples_window_sampled
            ON usage_samples(window, sampled_at)
            """;

    private static final String CURSOR_TABLE = """
            CREATE TABLE IF NOT EXISTS cursor (
              name TEXT PRIMARY KEY,
              value TEXT NOT NULL
            )
            """;

    @Inject
    JdbcClient db;

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String jdbcUrl;

    void init(@Observes StartupEvent event) {
        ensureParentDirectoryExists();
        db.sql(RAW_TABLE).update();
        db.sql(ANNOTATED_TABLE).update();
        db.sql(ANNOTATED_INDEX).update();
        db.sql(ANNOTATED_EVENT_TIME_INDEX).update();
        db.sql(ANNOTATED_CREDIT_TIME_INDEX).update();
        db.sql(USAGE_TABLE).update();
        db.sql(USAGE_WINDOW_TIME_INDEX).update();
        db.sql(CURSOR_TABLE).update();
        // Credit columns added after annotated_events shipped — migrate older DBs.
        ensureColumn("annotated_events", "rate_model", "TEXT");
        ensureColumn("annotated_events", "input_credits", "REAL");
        ensureColumn("annotated_events", "cached_credits", "REAL");
        ensureColumn("annotated_events", "output_credits", "REAL");
        ensureColumn("annotated_events", "total_credits", "REAL");
        // Attribution columns added later (trigger/originator/host).
        ensureColumn("annotated_events", "trigger", "TEXT");
        ensureColumn("annotated_events", "originator", "TEXT");
        ensureColumn("annotated_events", "host", "TEXT");
    }

    private void ensureColumn(String table, String column, String type) {
        boolean exists = db.sql("SELECT 1 FROM pragma_table_info(:t) WHERE name = :c")
                .param("t", table)
                .param("c", column)
                .query((rs, row) -> 1)
                .optional()
                .isPresent();
        if (!exists) {
            db.sql("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type).update();
        }
    }

    private void ensureParentDirectoryExists() {
        String prefix = "jdbc:sqlite:";
        if (!jdbcUrl.startsWith(prefix)) {
            return;
        }
        String rawPath = jdbcUrl.substring(prefix.length());
        int queryIndex = rawPath.indexOf('?');
        String dbPath = queryIndex >= 0 ? rawPath.substring(0, queryIndex) : rawPath;
        Path parent = Path.of(dbPath).toAbsolutePath().getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create SQLite directory: " + parent, e);
        }
    }
}
