package cero.ninja.ccc.codex;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.sqlite.SQLiteConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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

    @ConfigProperty(name = "codex.db.dir")
    String dbDir;

    @ConfigProperty(name = "codex.state5.path")
    Optional<String> state5Path;

    @ConfigProperty(name = "codex.logs2.path")
    Optional<String> logs2Path;

    @ConfigProperty(name = "codex.open.timeout-ms", defaultValue = "5000")
    int timeoutMs;

    @ConfigProperty(name = "codex.open.retries", defaultValue = "2")
    int retries;

    @ConfigProperty(name = "codex.open.backoff-ms", defaultValue = "500")
    long backoffMs;

    public List<Connection> openState5() throws SQLException {
        return openReadOnlyCandidates("state_5", candidatePaths("state_5.sqlite", state5Path));
    }

    public List<Connection> openLogs2() throws SQLException {
        return openReadOnlyCandidates("logs_2", candidatePaths("logs_2.sqlite", logs2Path));
    }

    @SafeVarargs
    private final List<String> candidatePaths(String fileName, Optional<String>... explicitPaths) {
        List<String> paths = new ArrayList<>();
        if (dbDir != null && !dbDir.isBlank()) {
            Path root = Path.of(dbDir);
            paths.add(root.resolve("sqlite").resolve(fileName).toString());
            paths.add(root.resolve(fileName).toString());
        }
        for (Optional<String> explicitPath : explicitPaths) {
            explicitPath.ifPresent(paths::add);
        }
        return uniquePaths(paths);
    }

    private List<Connection> openReadOnlyCandidates(String dbName, List<String> paths) throws SQLException {
        List<Connection> connections = new ArrayList<>();
        SQLException last = null;
        for (String path : paths) {
            if (!Files.isRegularFile(Path.of(path))) {
                continue;
            }
            try {
                connections.add(openReadOnly(path));
            } catch (SQLException e) {
                last = e;
            }
        }
        if (!connections.isEmpty()) {
            return connections;
        }
        if (last != null) {
            throw new SQLException("unable to open any Codex DB candidate (db=" + dbName + "): " + last, last);
        }
        throw new SQLException("no Codex DB candidate exists (db=" + dbName + ")");
    }

    private static List<String> uniquePaths(List<String> paths) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String path : paths) {
            if (path != null && !path.isBlank()) {
                unique.add(path);
            }
        }
        return List.copyOf(unique);
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
    public Optional<ThreadInfo> lookupThread(List<Connection> state5s, String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return Optional.empty();
        }
        for (Connection state5 : state5s) {
            Optional<ThreadInfo> thread = lookupThread(state5, threadId);
            if (thread.isPresent()) {
                return thread;
            }
        }
        return Optional.empty();
    }

    private Optional<ThreadInfo> lookupThread(Connection state5, String threadId) {
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
    public boolean threadHasSignature(List<Connection> logs2s, String threadId, String... signatures) {
        if (logs2s == null || threadId == null || threadId.isBlank() || signatures.length == 0) {
            return false;
        }
        for (Connection logs2 : logs2s) {
            if (threadHasSignature(logs2, threadId, signatures)) {
                return true;
            }
        }
        return false;
    }

    private boolean threadHasSignature(Connection logs2, String threadId, String... signatures) {
        if (logs2 == null) {
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
