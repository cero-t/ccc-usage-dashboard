package cero.ninja.agent.codexusage.codex;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.sqlite.SQLiteConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Opens Codex's own SQLite DBs ({@code state_5} / {@code logs_2}) read-only and
 * exposes the small lookups the annotate job needs.
 *
 * <p>Codex keeps these in WAL mode and writes them constantly. A read-only
 * opener has to map the {@code -shm} wal-index, and while Codex is mid-write or
 * checkpointing the open can transiently fail with {@code SQLITE_CANTOPEN}
 * ("unable to open database file"). {@code busy_timeout} only covers lock
 * contention <em>after</em> the file is open, not the open handshake, so the
 * open itself is retried a couple of times with a short backoff. The caller
 * treats a final failure as "retry next pass" (cursors are forward-only).
 */
@ApplicationScoped
public class CodexDb {

    @ConfigProperty(name = "codex.state5.path")
    String state5Path;

    @ConfigProperty(name = "codex.logs2.path")
    String logs2Path;

    @ConfigProperty(name = "codex.open.timeout-ms", defaultValue = "5000")
    int timeoutMs;

    @ConfigProperty(name = "codex.open.retries", defaultValue = "2")
    int retries;

    @ConfigProperty(name = "codex.open.backoff-ms", defaultValue = "500")
    long backoffMs;

    public Connection openState5() throws SQLException {
        return openReadOnly(state5Path);
    }

    public Connection openLogs2() throws SQLException {
        return openReadOnly(logs2Path);
    }

    private Connection openReadOnly(String path) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setBusyTimeout(timeoutMs);
        String url = "jdbc:sqlite:" + path;
        SQLException last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                return DriverManager.getConnection(url, config.toProperties());
            } catch (SQLException e) {
                last = e;
                if (attempt < retries) {
                    sleep(backoffMs);
                }
            }
        }
        throw new SQLException("unable to open Codex DB (db=" + path + "): " + last, last);
    }

    /** Per-thread metadata from {@code state_5.threads}, or empty if not found. */
    public Optional<ThreadInfo> lookupThread(Connection state5, String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return Optional.empty();
        }
        String sql = "SELECT model, reasoning_effort, source, title, cwd FROM threads WHERE id = ? LIMIT 1";
        try (PreparedStatement ps = state5.prepareStatement(sql)) {
            ps.setString(1, threadId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new ThreadInfo(
                            rs.getString("model"),
                            rs.getString("reasoning_effort"),
                            rs.getString("source"),
                            rs.getString("title"),
                            rs.getString("cwd")
                    ));
                }
            }
        } catch (SQLException e) {
            // Best-effort enrichment: a malformed/locked read just yields no metadata.
            return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * Whether any {@code logs_2} frame for this thread contains one of the given
     * body signatures (case-sensitive {@code LIKE %sig%}). Used by trigger
     * classification to tag ambient-suggestion / memory turns. Best-effort:
     * returns false on any read error or when {@code logs2} is unavailable.
     */
    public boolean threadHasSignature(Connection logs2, String threadId, String... signatures) {
        if (logs2 == null || threadId == null || threadId.isBlank() || signatures.length == 0) {
            return false;
        }
        StringBuilder clauses = new StringBuilder();
        for (int i = 0; i < signatures.length; i++) {
            if (i > 0) {
                clauses.append(" OR ");
            }
            clauses.append("feedback_log_body LIKE ?");
        }
        String sql = "SELECT 1 FROM logs WHERE thread_id = ? AND (" + clauses + ") LIMIT 1";
        try (PreparedStatement ps = logs2.prepareStatement(sql)) {
            ps.setString(1, threadId);
            for (int i = 0; i < signatures.length; i++) {
                ps.setString(i + 2, "%" + signatures[i] + "%");
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public record ThreadInfo(
            String model,
            String reasoningEffort,
            String source,
            String title,
            String cwd
    ) {}
}
