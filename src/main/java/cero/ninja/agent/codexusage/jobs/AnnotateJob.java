package cero.ninja.agent.codexusage.jobs;

import cero.ninja.agent.codexusage.codex.CodexDb;
import cero.ninja.agent.codexusage.credit.RateCard;
import cero.ninja.agent.codexusage.db.JdbcClient;
import cero.ninja.agent.codexusage.store.Cursors;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Job A. Reads raw OTLP log rows in id order from a forward-only cursor, parses
 * each, keeps the token-usage / error ones, enriches them from Codex's own DBs
 * (thread metadata from {@code state_5}, trigger signatures from {@code logs_2}),
 * and appends a derived row to {@code annotated_events}. Append-only: the raw
 * row is never mutated, so a parse bug can be fixed and replayed by rewinding
 * the cursor.
 */
@ApplicationScoped
public class AnnotateJob {

    private static final Logger LOG = Logger.getLogger(AnnotateJob.class);
    private static final String CURSOR = "annotate_log_id";

    // Trigger classification signatures. A turn the user didn't drive (not in
    // state_5.threads) is tagged ambient / memory / background by scanning its
    // logs_2 bodies. The ambient-suggestions JSON is usually embedded escaped
    // inside a Text { text: "..." } body, so we match both escaped and bare forms.
    private static final String[] AMBIENT_SIGNATURES = {
            "ambient suggestions", "\"suggestions\":[", "\\\"suggestions\\\":["
    };
    private static final String[] MEMORY_SIGNATURES = {
            "/memories/", "MEMORY.md", "memory_summary.md"
    };

    private static final String SELECT_RAW = """
            SELECT id, record_json FROM otel_log_records
            WHERE id > :cursor ORDER BY id ASC LIMIT :limit
            """;

    private static final String INSERT_ANNOTATED = """
            INSERT INTO annotated_events (
              source_log_id, time_unix_nano, event_name, thread_id, model,
              input_token_count, cached_input_token_count, output_token_count,
              error_message, thread_model, thread_reasoning_effort, thread_source,
              thread_title, thread_cwd, service_tier, rate_model,
              input_credits, cached_credits, output_credits, total_credits,
              trigger, originator, host, attributes_json
            ) VALUES (
              :source_log_id, :time_unix_nano, :event_name, :thread_id, :model,
              :input_token_count, :cached_input_token_count, :output_token_count,
              :error_message, :thread_model, :thread_reasoning_effort, :thread_source,
              :thread_title, :thread_cwd, :service_tier, :rate_model,
              :input_credits, :cached_credits, :output_credits, :total_credits,
              :trigger, :originator, :host, :attributes_json
            )
            """;

    @Inject
    JdbcClient db;

    @Inject
    Cursors cursors;

    @Inject
    CodexDb codex;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    RateCard rateCard;

    @ConfigProperty(name = "codex-usage-dashboard.annotate.batch-size", defaultValue = "500")
    int batchSize;

    @Scheduled(every = "{codex-usage-dashboard.annotate.every}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void run() {
        long cursor = cursors.getLong(CURSOR, 0);
        List<RawRow> rows = db.sql(SELECT_RAW)
                .param("cursor", cursor)
                .param("limit", batchSize)
                .query(RawRow.class)
                .list();
        if (rows.isEmpty()) {
            return;
        }

        // state_5 is small and the primary enrichment: if we can't open it, skip
        // the whole pass without advancing the cursor and retry next minute.
        Connection state5;
        try {
            state5 = codex.openState5();
        } catch (SQLException e) {
            LOG.warnf("annotate pass skipped (state_5 unavailable, retrying next pass): %s", e.getMessage());
            return;
        }

        // logs_2 is huge and constantly written; treat it as best-effort. It is
        // only used for trigger signatures; service_tier estimation is disabled.
        Connection logs2 = null;
        try {
            logs2 = codex.openLogs2();
        } catch (SQLException e) {
            LOG.debugf("logs_2 unavailable this pass, trigger falls back where needed: %s", e.getMessage());
        }

        Map<String, Optional<CodexDb.ThreadInfo>> threadCache = new HashMap<>();
        Map<String, String> triggerCache = new HashMap<>();
        long lastId = cursor;
        long failedId = -1;
        int processed = 0;
        int annotated = 0;
        try {
            for (RawRow row : rows) {
                try {
                    if (annotateOne(row, state5, logs2, threadCache, triggerCache)) {
                        annotated++;
                    }
                    lastId = row.id();
                    processed++;
                } catch (Exception e) {
                    // Raw stays intact for replay. Stop before this row so parser/DB bugs
                    // remain recoverable without manual cursor rewind.
                    failedId = row.id();
                    LOG.warnf("annotate failed for raw id=%d; cursor remains at %d and row will be retried next pass: %s",
                            failedId, lastId, e.getMessage());
                    break;
                }
            }
        } finally {
            close(state5);
            close(logs2);
        }

        if (lastId != cursor) {
            cursors.setLong(CURSOR, lastId);
        }
        if (failedId >= 0) {
            LOG.infof("annotate: stopped before raw id=%d after %d processed row(s), %d annotated (cursor=%d)",
                    failedId, processed, annotated, lastId);
        } else if (annotated > 0) {
            LOG.infof("annotate: %d/%d processed rows -> annotated_events (cursor=%d)",
                    annotated, processed, lastId);
        }
    }

    /** Returns true if a derived row was appended. */
    private boolean annotateOne(
            RawRow row,
            Connection state5,
            Connection logs2,
            Map<String, Optional<CodexDb.ThreadInfo>> threadCache,
            Map<String, String> triggerCache
    ) throws Exception {
        JsonNode root = objectMapper.readTree(row.recordJson());
        JsonNode attrs = root.path("attributes");
        JsonNode resource = root.path("resource_attributes");

        Long inputTokens = optLong(attrs, "input_token_count");
        String errorMessage = optString(attrs, "error.message");
        if (inputTokens == null && (errorMessage == null || errorMessage.isBlank())) {
            return false; // not a token-usage / error record — decided, skipped
        }

        // The Codex OTel export carries the thread identifier as conversation.id;
        // it joins to state_5.threads.id and logs_2.logs.thread_id. (Older guess
        // "thread_id" is kept as a fallback but is not what Codex emits.)
        String threadId = firstNonBlank(optString(attrs, "conversation.id"),
                optString(attrs, "thread_id"),
                optString(resource, "thread_id"));

        Optional<CodexDb.ThreadInfo> thread = threadId == null
                ? Optional.empty()
                : threadCache.computeIfAbsent(threadId, t -> codex.lookupThread(state5, t));

        // Attribute the turn (user / ambient / memory / background).
        // Cached per thread within the pass.
        String trigger = classifyTrigger(threadId, thread.isPresent(), logs2, triggerCache);

        CodexDb.ThreadInfo ti = thread.orElse(null);
        String eventModel = optString(attrs, "model");
        Long cachedTokens = optLong(attrs, "cached_token_count");
        Long outputTokens = optLong(attrs, "output_token_count");

        // Bill against the event's model, falling back to the thread's model.
        // Credits are always standard-rate: OTLP completion rows do not carry
        // turn_id/submission_id, so there is no reliable per-turn service tier to
        // surcharge a Fast/priority turn (see dev_docs/codex-data-model.md).
        String rateModel = firstNonBlank(eventModel, ti == null ? null : ti.model());
        RateCard.Credits credits = inputTokens == null
                ? null
                : rateCard.compute(rateModel, inputTokens, cachedTokens, outputTokens);

        db.sql(INSERT_ANNOTATED)
                .param("source_log_id", row.id())
                .param("time_unix_nano", resolveTimeNano(root))
                .param("event_name", optString(attrs, "event.name"))
                .param("thread_id", threadId)
                .param("model", eventModel)
                .param("input_token_count", inputTokens)
                .param("cached_input_token_count", cachedTokens)
                .param("output_token_count", outputTokens)
                .param("error_message", errorMessage)
                .param("thread_model", ti == null ? null : ti.model())
                .param("thread_reasoning_effort", ti == null ? null : ti.reasoningEffort())
                .param("thread_source", ti == null ? null : ti.source())
                .param("thread_title", ti == null ? null : ti.title())
                .param("thread_cwd", ti == null ? null : ti.cwd())
                .param("service_tier", null)
                .param("rate_model", rateModel)
                .param("input_credits", credits == null ? null : credits.input())
                .param("cached_credits", credits == null ? null : credits.cached())
                .param("output_credits", credits == null ? null : credits.output())
                .param("total_credits", credits == null ? null : credits.total())
                .param("trigger", trigger)
                .param("originator", optString(attrs, "originator"))
                .param("host", optString(resource, "host.name"))
                .param("attributes_json", attrs.isMissingNode() ? null : attrs.toString())
                .update();
        return true;
    }

    /**
     * Attribute a turn to its trigger: null thread → background; thread present
     * in state_5 → user; otherwise the logs_2 body decides ambient / memory,
     * defaulting to background.
     */
    private String classifyTrigger(String threadId, boolean knownThread, Connection logs2,
                                   Map<String, String> cache) {
        if (threadId == null || threadId.isBlank()) {
            return "background";
        }
        if (knownThread) {
            return "user";
        }
        return cache.computeIfAbsent(threadId, t -> {
            if (codex.threadHasSignature(logs2, t, AMBIENT_SIGNATURES)) {
                return "ambient";
            }
            if (codex.threadHasSignature(logs2, t, MEMORY_SIGNATURES)) {
                return "memory";
            }
            return "background";
        });
    }

    /**
     * Real event time in epoch-nanos. The OTel records carry {@code time_unix_nano}
     * = 0 (Codex doesn't set it); the wall-clock is in {@code observed_time_unix_nano}.
     */
    private static Long resolveTimeNano(JsonNode root) {
        Long t = optLong(root, "time_unix_nano");
        if (t != null && t > 0) {
            return t;
        }
        return optLong(root, "observed_time_unix_nano");
    }

    private static Long optLong(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.asLong();
        }
        if (v.isTextual() && !v.asText().isBlank()) {
            try {
                return Long.parseLong(v.asText().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String optString(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.isValueNode() ? v.asText() : v.toString();
        return s.isBlank() ? null : s;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static void close(Connection conn) {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException ignored) {
                // best-effort
            }
        }
    }

    public record RawRow(long id, String recordJson) {}
}
