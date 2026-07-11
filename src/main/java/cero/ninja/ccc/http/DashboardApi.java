package cero.ninja.ccc.http;

import cero.ninja.ccc.db.JdbcClient;
import cero.ninja.ccc.store.Cursors;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * Read-only JSON API backing the embedded dashboard. Every query reads the local
 * {@code codex-usage-dashboard.sqlite} append-only tables on the fly — no rollup table —
 * so the whole surface ports to Go as a set of SQL strings + {@code net/http} handlers.
 *
 * <p>Time grain note: {@code annotated_events.time_unix_nano} is currently 0 for the
 * real traffic (the OTel records carry no event timestamp), so buckets fall back to
 * {@code annotated_at} via {@code NULLIF(time_unix_nano, 0)}. {@code annotated_at} /
 * {@code sampled_at} are stored UTC (SQLite {@code CURRENT_TIMESTAMP}); the queries
 * convert to {@code 'localtime'} for display.
 */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class DashboardApi {

    @Inject
    JdbcClient db;

    @Inject
    Cursors cursors;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "codex-usage-dashboard.codex.enabled", defaultValue = "true")
    boolean codexEnabled;

    @ConfigProperty(name = "codex-usage-dashboard.claude.enabled", defaultValue = "true")
    boolean claudeEnabled;

    // ---- DTOs (mapped from result-set column labels by RecordMapper) ----

    public record Config(
            boolean codexEnabled,
            boolean claudeEnabled) {}

    public record Summary(
            double totalCredits,
            double totalCostUsd,
            long totalInputTokens,
            long totalCachedInputTokens,
            long totalOutputTokens,
            long totalEvents,
            long eventsWithCredits,
            long eventsWithCost,
            long rawRecords,
            long annotateCursor,
            long backlog,
            String lastReceivedAt,
            List<UsageLatest> usage) {}

    public record UsageLatest(
            String window,
            String planType,
            Double usedPercent,
            Double remainingPercent,
            Long resetsAt,
            String sampledAt) {}

    public record ModelCredits(
            String rateModel,
            long n,
            Long inTok,
            Long outTok,
            double credits) {}

    public record CreditPoint(
            String bucket,
            double credits,
            long n) {}

    public record RecentEvent(
            long id,
            String ts,
            String eventName,
            String threadId,
            String model,
            String rateModel,
            Integer inputTokenCount,
            Integer uncachedInputTokenCount,
            Integer cachedInputTokenCount,
            Integer outputTokenCount,
            Integer rawInputTokenCount,
            Integer cacheReadTokenCount,
            Integer cacheCreationTokenCount,
            Double totalCredits,
            Double costUsd,
            String sourceTool,
            String requestId,
            String trigger,
            String threadSource,
            String serviceTier,
            String errorMessage) {}

    public record ErrorEvent(
            long id,
            String ts,
            String eventName,
            String trigger,
            String endpoint,
            Integer statusCode,
            String errorMessage) {}

    /** The raw OTLP record (verbatim JSON) behind one annotated event, for the drill-down view. */
    public record RawEvent(
            long id,
            long sourceLogId,
            String eventName,
            String threadId,
            String receivedAt,
            JsonNode record) {}

    public record UsagePoint(
            String sampledAt,
            Double usedPercent) {}

    public record TriggerCredits(
            String trigger,
            long n,
            double credits) {}

    public record ModelTriggerCredits(
            String rateModel,
            String trigger,
            long n,
            double credits) {}

    /** One (time-bucket, series-key, credits) cell for stacked time-series charts. */
    public record SeriesPoint(
            String bucket,
            String series,
            double credits) {}

    public record CostByModel(
            String rateModel,
            long n,
            Long inTok,
            Long outTok,
            double costUsd) {}

    public record CostByTrigger(
            String trigger,
            long n,
            double costUsd) {}

    public record CostByModelTrigger(
            String rateModel,
            String trigger,
            long n,
            double costUsd) {}

    public record TokenByModel(
            String rateModel,
            long n,
            long tokens) {}

    public record TokenByTrigger(
            String trigger,
            long n,
            long tokens) {}

    public record TokenByModelTrigger(
            String rateModel,
            String trigger,
            long n,
            long tokens) {}

    public record CostSeriesPoint(
            String bucket,
            String series,
            double costUsd) {}

    public record TokenSeriesPoint(
            String bucket,
            String series,
            long tokens) {}

    public record ConversationUsage(
            String sourceTool,
            String lastTs,
            String threadId,
            String rateModel,
            String trigger,
            long n,
            Long inputTokenCount,
            Long uncachedInputTokenCount,
            Long cachedInputTokenCount,
            Long outputTokenCount,
            Long rawInputTokenCount,
            Long cacheReadTokenCount,
            Long cacheCreationTokenCount,
            Double totalCredits,
            Double costUsd) {}

    private record TimeRange(String id, long seconds, int months) {
        long sinceEpoch(Instant now) {
            if (months > 0) {
                return ZonedDateTime.ofInstant(now, ZoneOffset.UTC)
                        .minusMonths(months)
                        .toEpochSecond();
            }
            return now.minusSeconds(seconds).getEpochSecond();
        }
    }

    private record TimeGrain(String id, int seconds) {}

    private record TimeBounds(long sinceEpoch, long untilEpoch) {}

    private static final TimeRange DEFAULT_RANGE = new TimeRange("6h", 6 * 60 * 60L, 0);
    private static final TimeGrain DEFAULT_GRAIN = new TimeGrain("5m", 5 * 60);

    private static final Map<String, TimeRange> RANGES = Map.ofEntries(
            Map.entry("15m", new TimeRange("15m", 15 * 60L, 0)),
            Map.entry("30m", new TimeRange("30m", 30 * 60L, 0)),
            Map.entry("1h", new TimeRange("1h", 60 * 60L, 0)),
            Map.entry("3h", new TimeRange("3h", 3 * 60 * 60L, 0)),
            Map.entry("6h", DEFAULT_RANGE),
            Map.entry("12h", new TimeRange("12h", 12 * 60 * 60L, 0)),
            Map.entry("1d", new TimeRange("1d", 24 * 60 * 60L, 0)),
            Map.entry("3d", new TimeRange("3d", 3 * 24 * 60 * 60L, 0)),
            Map.entry("1w", new TimeRange("1w", 7 * 24 * 60 * 60L, 0)),
            Map.entry("30d", new TimeRange("30d", 30 * 24 * 60 * 60L, 0)),
            Map.entry("6mo", new TimeRange("6mo", 0, 6)));

    private static final Map<String, TimeGrain> GRAINS = Map.ofEntries(
            Map.entry("1m", new TimeGrain("1m", 60)),
            Map.entry("5m", DEFAULT_GRAIN),
            Map.entry("30m", new TimeGrain("30m", 30 * 60)),
            Map.entry("1h", new TimeGrain("1h", 60 * 60)),
            Map.entry("12h", new TimeGrain("12h", 12 * 60 * 60)),
            Map.entry("1d", new TimeGrain("1d", 24 * 60 * 60)));

    // Event time in UTC epoch seconds. time_unix_nano can be 0, so fall back to
    // annotated_at (stored UTC) via NULLIF — see RecordMapper/AnnotateJob notes.
    private static final String EVENT_EPOCH =
            "CAST(COALESCE(NULLIF(time_unix_nano, 0) / 1000000000, strftime('%s', annotated_at)) AS INTEGER)";

    private static final String TIME_FILTER = EVENT_EPOCH + " >= :sinceEpoch AND " + EVENT_EPOCH + " < :untilEpoch";
    private static final String SOURCE_FILTER = "COALESCE(source_tool, 'codex') = :sourceTool";

    // Selectable bucket in local wall time, rounded down by :grainSeconds. The
    // second strftime intentionally omits 'localtime' because the intermediate
    // epoch already represents the local wall-clock fields.
    private static final String BUCKET_EPOCH =
            "((CAST(strftime('%s', datetime(" + EVENT_EPOCH
            + ", 'unixepoch', 'localtime')) AS INTEGER) / :grainSeconds) * :grainSeconds)";
    private static final String BUCKET =
            "strftime('%Y-%m-%d %H:%M', " + BUCKET_EPOCH + ", 'unixepoch')";

    // ---- Endpoints ----

    @GET
    @Path("/config")
    public Config config() {
        return new Config(codexEnabled, claudeEnabled);
    }

    @GET
    @Path("/summary")
    public Summary summary(
            @QueryParam("range") @DefaultValue("6h") String range,
            @QueryParam("source") @DefaultValue("codex") String source,
            @QueryParam("from") Long fromEpoch,
            @QueryParam("to") Long toEpoch) {
        String sourceTool = sourceTool(source);
        TimeBounds time = timeBounds(range, fromEpoch, toEpoch);
        double totalCredits = db.sql(
                        "SELECT round(coalesce(sum(total_credits), 0), 4) FROM annotated_events "
                        + "WHERE total_credits IS NOT NULL AND " + TIME_FILTER + " AND " + SOURCE_FILTER)
                .param("sinceEpoch", time.sinceEpoch())
                .param("untilEpoch", time.untilEpoch())
                .param("sourceTool", sourceTool)
                .query((rs, row) -> rs.getDouble(1)).single();
        double totalCostUsd = db.sql(
                        "SELECT round(coalesce(sum(cost_usd), 0), 6) FROM annotated_events "
                        + "WHERE cost_usd IS NOT NULL AND " + TIME_FILTER + " AND " + SOURCE_FILTER)
                .param("sinceEpoch", time.sinceEpoch())
                .param("untilEpoch", time.untilEpoch())
                .param("sourceTool", sourceTool)
                .query((rs, row) -> rs.getDouble(1)).single();
        long totalEvents = db.sql(
                        "SELECT count(*) FROM annotated_events WHERE " + TIME_FILTER + " AND " + SOURCE_FILTER)
                .param("sinceEpoch", time.sinceEpoch())
                .param("untilEpoch", time.untilEpoch())
                .param("sourceTool", sourceTool)
                .query((rs, row) -> rs.getLong(1)).single();
        long eventsWithCredits = db.sql(
                        "SELECT count(*) FROM annotated_events "
                        + "WHERE total_credits IS NOT NULL AND " + TIME_FILTER + " AND " + SOURCE_FILTER)
                .param("sinceEpoch", time.sinceEpoch())
                .param("untilEpoch", time.untilEpoch())
                .param("sourceTool", sourceTool)
                .query((rs, row) -> rs.getLong(1)).single();
        long eventsWithCost = db.sql(
                        "SELECT count(*) FROM annotated_events "
                        + "WHERE cost_usd IS NOT NULL AND " + TIME_FILTER + " AND " + SOURCE_FILTER)
                .param("sinceEpoch", time.sinceEpoch())
                .param("untilEpoch", time.untilEpoch())
                .param("sourceTool", sourceTool)
                .query((rs, row) -> rs.getLong(1)).single();
        long totalInputTokens = db.sql(
                        "SELECT coalesce(sum(input_token_count), 0) FROM annotated_events "
                        + "WHERE input_token_count IS NOT NULL AND " + TIME_FILTER + " AND " + SOURCE_FILTER)
                .param("sinceEpoch", time.sinceEpoch())
                .param("untilEpoch", time.untilEpoch())
                .param("sourceTool", sourceTool)
                .query((rs, row) -> rs.getLong(1)).single();
        long totalCachedInputTokens = db.sql(
                        "SELECT coalesce(sum(cached_input_token_count), 0) FROM annotated_events "
                        + "WHERE cached_input_token_count IS NOT NULL AND " + TIME_FILTER + " AND " + SOURCE_FILTER)
                .param("sinceEpoch", time.sinceEpoch())
                .param("untilEpoch", time.untilEpoch())
                .param("sourceTool", sourceTool)
                .query((rs, row) -> rs.getLong(1)).single();
        long totalOutputTokens = db.sql(
                        "SELECT coalesce(sum(output_token_count), 0) FROM annotated_events "
                        + "WHERE output_token_count IS NOT NULL AND " + TIME_FILTER + " AND " + SOURCE_FILTER)
                .param("sinceEpoch", time.sinceEpoch())
                .param("untilEpoch", time.untilEpoch())
                .param("sourceTool", sourceTool)
                .query((rs, row) -> rs.getLong(1)).single();
        long rawRecords = db.sql("SELECT count(*) FROM otel_log_records")
                .query((rs, row) -> rs.getLong(1)).single();
        String lastReceivedAt = db.sql(
                        "SELECT datetime(max(received_at), 'localtime') FROM otel_log_records")
                .query((rs, row) -> rs.getString(1)).optional().orElse(null);
        long annotateCursor = cursors.getLong("annotate_log_id", 0);
        // True unprocessed backlog: raw rows past the cursor. (rawRecords is a count and
        // annotateCursor is the last-processed log *id* — subtracting them mixes units and
        // can even go negative when autoincrement skips ids.)
        long backlog = db.sql("SELECT count(*) FROM otel_log_records WHERE id > :cursor")
                .param("cursor", annotateCursor)
                .query((rs, row) -> rs.getLong(1)).single();

        return new Summary(totalCredits, totalCostUsd, totalInputTokens, totalCachedInputTokens, totalOutputTokens,
                totalEvents, eventsWithCredits, eventsWithCost, rawRecords,
                annotateCursor, backlog, lastReceivedAt, usageLatest());
    }

    @GET
    @Path("/credits/by-model")
    public List<ModelCredits> creditsByModel(
            @QueryParam("range") @DefaultValue("6h") String range,
            @QueryParam("source") @DefaultValue("codex") String source,
            @QueryParam("from") Long fromEpoch,
            @QueryParam("to") Long toEpoch) {
        TimeBounds time = timeBounds(range, fromEpoch, toEpoch);
        return db.sql("""
                SELECT rate_model,
                       count(*)                       AS n,
                       sum(input_token_count)         AS in_tok,
                       sum(output_token_count)        AS out_tok,
                       round(sum(total_credits), 4)   AS credits
                FROM annotated_events
                WHERE total_credits IS NOT NULL
                  AND %s
                  AND %s
                GROUP BY rate_model
                ORDER BY credits DESC
                """.formatted(TIME_FILTER, SOURCE_FILTER))
                .param("sinceEpoch", time.sinceEpoch())
                .param("untilEpoch", time.untilEpoch())
                .param("sourceTool", sourceTool(source))
                .query(ModelCredits.class).list();
    }

    @GET
    @Path("/credits/timeseries")
    public List<CreditPoint> creditsTimeseries(
            @QueryParam("range") @DefaultValue("6h") String range,
            @QueryParam("source") @DefaultValue("codex") String source,
            @QueryParam("grain") @DefaultValue("5m") String grain,
            @QueryParam("from") Long fromEpoch,
            @QueryParam("to") Long toEpoch) {
        TimeBounds time = timeBounds(range, fromEpoch, toEpoch);
        return db.sql("""
                SELECT %s                                    AS bucket,
                       round(sum(total_credits), 4)           AS credits,
                       count(*)                               AS n
                FROM annotated_events
                WHERE total_credits IS NOT NULL
                  AND %s
                  AND %s
                GROUP BY bucket
                ORDER BY bucket
                """.formatted(BUCKET, TIME_FILTER, SOURCE_FILTER))
                .param("sinceEpoch", time.sinceEpoch())
                .param("untilEpoch", time.untilEpoch())
                .param("sourceTool", sourceTool(source))
                .param("grainSeconds", grain(grain).seconds())
                .query(CreditPoint.class).list();
    }

    /** Recent successful completions (token-bearing, no error). Errors live in {@link #recentErrors}. */
    @GET
    @Path("/events/completions")
    public List<RecentEvent> recentCompletions(
            @QueryParam("limit") @DefaultValue("25") int limit,
            @QueryParam("range") @DefaultValue("6h") String range,
            @QueryParam("source") @DefaultValue("codex") String source,
            @QueryParam("from") Long fromEpoch,
            @QueryParam("to") Long toEpoch) {
        TimeBounds time = timeBounds(range, fromEpoch, toEpoch);
        return db.sql("""
                SELECT id,
                       COALESCE(datetime(NULLIF(time_unix_nano, 0) / 1000000000, 'unixepoch', 'localtime'),
                                datetime(annotated_at, 'localtime'))  AS ts,
                       event_name,
                       thread_id,
                       model,
                       rate_model,
                       input_token_count,
                       max(coalesce(input_token_count, 0) - coalesce(cached_input_token_count, 0), 0)
                         AS uncached_input_token_count,
                       cached_input_token_count,
                       output_token_count,
                       CAST(json_extract(attributes_json, '$.input_tokens') AS INTEGER) AS raw_input_token_count,
                       CAST(json_extract(attributes_json, '$.cache_read_tokens') AS INTEGER) AS cache_read_token_count,
                       CAST(json_extract(attributes_json, '$.cache_creation_tokens') AS INTEGER) AS cache_creation_token_count,
                       total_credits,
                       cost_usd,
                       COALESCE(source_tool, 'codex') AS source_tool,
                       request_id,
                       trigger,
                       thread_source,
                       service_tier,
                       error_message
                FROM annotated_events
                WHERE error_message IS NULL
                  AND %s
                  AND %s
                ORDER BY id DESC
                LIMIT :limit
                """.formatted(TIME_FILTER, SOURCE_FILTER))
                .param("sinceEpoch", time.sinceEpoch())
                .param("untilEpoch", time.untilEpoch())
                .param("sourceTool", sourceTool(source))
                .param("limit", clamp(limit, 1, 500))
                .query(RecentEvent.class).list();
    }

    @GET
    @Path("/events/conversations")
    public List<ConversationUsage> conversationUsage(
            @QueryParam("limit") @DefaultValue("25") int limit,
            @QueryParam("range") @DefaultValue("6h") String range,
            @QueryParam("source") @DefaultValue("codex") String source,
            @QueryParam("from") Long fromEpoch,
            @QueryParam("to") Long toEpoch) {
        TimeBounds time = timeBounds(range, fromEpoch, toEpoch);
        return db.sql("""
                SELECT COALESCE(source_tool, 'codex') AS source_tool,
                       COALESCE(datetime(max(%1$s), 'unixepoch', 'localtime'), datetime(max(annotated_at), 'localtime')) AS last_ts,
                       thread_id,
                       COALESCE(rate_model, model, 'unknown') AS rate_model,
                       COALESCE(trigger, 'unknown') AS trigger,
                       count(*) AS n,
                       sum(input_token_count) AS input_token_count,
                       sum(max(coalesce(input_token_count, 0) - coalesce(cached_input_token_count, 0), 0))
                         AS uncached_input_token_count,
                       sum(cached_input_token_count) AS cached_input_token_count,
                       sum(output_token_count) AS output_token_count,
                       sum(CAST(json_extract(attributes_json, '$.input_tokens') AS INTEGER)) AS raw_input_token_count,
                       sum(CAST(json_extract(attributes_json, '$.cache_read_tokens') AS INTEGER)) AS cache_read_token_count,
                       sum(CAST(json_extract(attributes_json, '$.cache_creation_tokens') AS INTEGER)) AS cache_creation_token_count,
                       round(sum(total_credits), 4) AS total_credits,
                       round(sum(cost_usd), 6) AS cost_usd
                FROM annotated_events
                WHERE (input_token_count IS NOT NULL OR output_token_count IS NOT NULL
                       OR total_credits IS NOT NULL OR cost_usd IS NOT NULL)
                  AND %2$s
                  AND %3$s
                GROUP BY source_tool, thread_id, rate_model, trigger
                ORDER BY max(%1$s) DESC
                LIMIT :limit
                """.formatted(EVENT_EPOCH, TIME_FILTER, SOURCE_FILTER))
                .param("sinceEpoch", time.sinceEpoch())
                .param("untilEpoch", time.untilEpoch())
                .param("sourceTool", sourceTool(source))
                .param("limit", clamp(limit, 1, 200))
                .query(ConversationUsage.class).list();
    }

    /**
     * Recent failed events (anything carrying an {@code error.message}) — auth 401s on
     * {@code api_request}/{@code websocket_connect}, model-not-supported 400s on
     * {@code sse_event}, etc. {@code endpoint}/{@code statusCode} are pulled from the
     * stored attributes (present for the HTTP errors; null for the JSON-bodied sse errors,
     * whose status sits inside the message — the UI parses that out).
     */
    @GET
    @Path("/events/errors")
    public List<ErrorEvent> recentErrors(
            @QueryParam("limit") @DefaultValue("25") int limit,
            @QueryParam("range") @DefaultValue("6h") String range,
            @QueryParam("source") @DefaultValue("codex") String source,
            @QueryParam("from") Long fromEpoch,
            @QueryParam("to") Long toEpoch) {
        TimeBounds time = timeBounds(range, fromEpoch, toEpoch);
        return db.sql("""
                SELECT id,
                       COALESCE(datetime(NULLIF(time_unix_nano, 0) / 1000000000, 'unixepoch', 'localtime'),
                                datetime(annotated_at, 'localtime'))      AS ts,
                       event_name,
                       trigger,
                       json_extract(attributes_json, '$.endpoint')        AS endpoint,
                       json_extract(attributes_json, '$."http.response.status_code"') AS status_code,
                       error_message
                FROM annotated_events
                WHERE error_message IS NOT NULL
                  AND %s
                  AND %s
                ORDER BY id DESC
                LIMIT :limit
                """.formatted(TIME_FILTER, SOURCE_FILTER))
                .param("sinceEpoch", time.sinceEpoch())
                .param("untilEpoch", time.untilEpoch())
                .param("sourceTool", sourceTool(source))
                .param("limit", clamp(limit, 1, 500))
                .query(ErrorEvent.class).list();
    }

    /**
     * Drill-down: the verbatim raw OTLP record behind one annotated event, joined
     * back through {@code source_log_id}. Powers the click-to-inspect on the Recent
     * events table so a row with no tokens/model (e.g. an {@code api_request} that
     * got an HTTP 401) can be opened to see exactly what arrived. 404 if unknown.
     */
    @GET
    @Path("/events/{id}/raw")
    public Response eventRaw(@PathParam("id") long id) {
        var found = db.sql("""
                SELECT a.id                                   AS id,
                       a.source_log_id                        AS source_log_id,
                       a.event_name                           AS event_name,
                       a.thread_id                            AS thread_id,
                       datetime(r.received_at, 'localtime')   AS received_at,
                       r.record_json                          AS record_json
                FROM annotated_events a
                JOIN otel_log_records r ON r.id = a.source_log_id
                WHERE a.id = :id
                """)
                .param("id", id)
                .query((rs, row) -> new RawEvent(
                        rs.getLong("id"),
                        rs.getLong("source_log_id"),
                        rs.getString("event_name"),
                        rs.getString("thread_id"),
                        rs.getString("received_at"),
                        parseJsonLenient(rs.getString("record_json"))))
                .optional();
        if (found.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "event " + id + " not found")).build();
        }
        return Response.ok(found.get()).build();
    }

    /** Parse stored JSON to a node; if it somehow isn't valid JSON, wrap it as a string node. */
    private JsonNode parseJsonLenient(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.getNodeFactory().textNode(json);
        }
    }

    @GET
    @Path("/usage/latest")
    public List<UsageLatest> usageLatest() {
        return db.sql("""
                SELECT window,
                       plan_type,
                       used_percent,
                       remaining_percent,
                       resets_at,
                       datetime(sampled_at, 'localtime')  AS sampled_at
                FROM usage_samples
                WHERE id IN (SELECT max(id) FROM usage_samples GROUP BY window)
                ORDER BY window
                """).query(UsageLatest.class).list();
    }

    @GET
    @Path("/usage/history")
    public List<UsagePoint> usageHistory(
            @QueryParam("window") @DefaultValue("primary") String window,
            @QueryParam("range") @DefaultValue("6h") String range,
            @QueryParam("grain") @DefaultValue("5m") String grain,
            @QueryParam("from") Long fromEpoch,
            @QueryParam("to") Long toEpoch) {
        TimeBounds time = timeBounds(range, fromEpoch, toEpoch);
        return db.sql("""
                WITH points AS (
                  SELECT id,
                         ((CAST(strftime('%s', datetime(sampled_at, 'localtime')) AS INTEGER) / :grainSeconds) * :grainSeconds) AS bucket_epoch
                  FROM usage_samples
                  WHERE window = :window
                    AND sampled_at >= datetime(:sinceEpoch, 'unixepoch')
                    AND sampled_at < datetime(:untilEpoch, 'unixepoch')
                ),
                last_sample AS (
                  SELECT bucket_epoch, max(id) AS id
                  FROM points
                  GROUP BY bucket_epoch
                )
                SELECT strftime('%Y-%m-%d %H:%M', last_sample.bucket_epoch, 'unixepoch') AS sampled_at,
                       usage_samples.used_percent
                FROM last_sample
                JOIN usage_samples ON usage_samples.id = last_sample.id
                ORDER BY last_sample.bucket_epoch
                """)
                .param("window", window)
                .param("sinceEpoch", time.sinceEpoch())
                .param("untilEpoch", time.untilEpoch())
                .param("grainSeconds", grain(grain).seconds())
                .query(UsagePoint.class).list();
    }

    @GET
    @Path("/credits/by-trigger")
    public List<TriggerCredits> creditsByTrigger(
            @QueryParam("range") @DefaultValue("6h") String range,
            @QueryParam("source") @DefaultValue("codex") String source,
            @QueryParam("from") Long fromEpoch,
            @QueryParam("to") Long toEpoch) {
        TimeBounds time = timeBounds(range, fromEpoch, toEpoch);
        return db.sql("""
                SELECT COALESCE(trigger, 'unknown')  AS trigger,
                       count(*)                       AS n,
                       round(sum(total_credits), 4)   AS credits
                FROM annotated_events
                WHERE total_credits IS NOT NULL
                  AND %s
                  AND %s
                GROUP BY trigger
                ORDER BY credits DESC
                """.formatted(TIME_FILTER, SOURCE_FILTER))
                .param("sinceEpoch", time.sinceEpoch())
                .param("untilEpoch", time.untilEpoch())
                .param("sourceTool", sourceTool(source))
                .query(TriggerCredits.class).list();
    }

    @GET
    @Path("/credits/by-model-trigger")
    public List<ModelTriggerCredits> creditsByModelTrigger(
            @QueryParam("range") @DefaultValue("6h") String range,
            @QueryParam("source") @DefaultValue("codex") String source,
            @QueryParam("from") Long fromEpoch,
            @QueryParam("to") Long toEpoch) {
        TimeBounds time = timeBounds(range, fromEpoch, toEpoch);
        return db.sql("""
                SELECT rate_model,
                       COALESCE(trigger, 'unknown')  AS trigger,
                       count(*)                       AS n,
                       round(sum(total_credits), 4)   AS credits
                FROM annotated_events
                WHERE total_credits IS NOT NULL
                  AND %s
                  AND %s
                GROUP BY rate_model, trigger
                ORDER BY credits DESC
                """.formatted(TIME_FILTER, SOURCE_FILTER))
                .param("sinceEpoch", time.sinceEpoch())
                .param("untilEpoch", time.untilEpoch())
                .param("sourceTool", sourceTool(source))
                .query(ModelTriggerCredits.class).list();
    }

    @GET
    @Path("/credits/by-trigger-timeseries")
    public List<SeriesPoint> creditsByTriggerOverTime(
            @QueryParam("range") @DefaultValue("6h") String range,
            @QueryParam("source") @DefaultValue("codex") String source,
            @QueryParam("grain") @DefaultValue("5m") String grain,
            @QueryParam("from") Long fromEpoch,
            @QueryParam("to") Long toEpoch) {
        TimeBounds time = timeBounds(range, fromEpoch, toEpoch);
        return db.sql("""
                SELECT %s                            AS bucket,
                       COALESCE(trigger, 'unknown')  AS series,
                       round(sum(total_credits), 4)  AS credits
                FROM annotated_events
                WHERE total_credits IS NOT NULL
                  AND %s
                  AND %s
                GROUP BY bucket, series
                ORDER BY bucket
                """.formatted(BUCKET, TIME_FILTER, SOURCE_FILTER))
                .param("sinceEpoch", time.sinceEpoch())
                .param("untilEpoch", time.untilEpoch())
                .param("sourceTool", sourceTool(source))
                .param("grainSeconds", grain(grain).seconds())
                .query(SeriesPoint.class).list();
    }

    /**
     * Credits split into the three additive types (uncached_input / cached_input /
     * output) per selected bucket — the stored per-component credit columns unpivoted.
     */
    @GET
    @Path("/credits/by-type-timeseries")
    public List<SeriesPoint> creditsByTypeOverTime(
            @QueryParam("range") @DefaultValue("6h") String range,
            @QueryParam("source") @DefaultValue("codex") String source,
            @QueryParam("grain") @DefaultValue("5m") String grain,
            @QueryParam("from") Long fromEpoch,
            @QueryParam("to") Long toEpoch) {
        TimeBounds time = timeBounds(range, fromEpoch, toEpoch);
        String b = BUCKET;
        return db.sql("""
                SELECT bucket, series, round(sum(credits), 4) AS credits FROM (
                  SELECT %1$s AS bucket, 'uncached_input' AS series, input_credits  AS credits
                    FROM annotated_events WHERE input_credits  IS NOT NULL AND %2$s AND %3$s
                  UNION ALL
                  SELECT %1$s, 'cached_input',  cached_credits FROM annotated_events WHERE cached_credits IS NOT NULL
                    AND %2$s AND %3$s
                  UNION ALL
                  SELECT %1$s, 'output',        output_credits FROM annotated_events WHERE output_credits IS NOT NULL
                    AND %2$s AND %3$s
                )
                GROUP BY bucket, series
                ORDER BY bucket
                """.formatted(b, TIME_FILTER, SOURCE_FILTER))
                .param("sinceEpoch", time.sinceEpoch())
                .param("untilEpoch", time.untilEpoch())
                .param("sourceTool", sourceTool(source))
                .param("grainSeconds", grain(grain).seconds())
                .query(SeriesPoint.class).list();
    }

    @GET
    @Path("/cost/by-model")
    public List<CostByModel> costByModel(
            @QueryParam("range") @DefaultValue("6h") String range,
            @QueryParam("source") @DefaultValue("codex") String source,
            @QueryParam("from") Long fromEpoch,
            @QueryParam("to") Long toEpoch) {
        TimeBounds time = timeBounds(range, fromEpoch, toEpoch);
        return db.sql("""
                SELECT COALESCE(rate_model, model, 'unknown') AS rate_model,
                       count(*)                               AS n,
                       sum(input_token_count)                 AS in_tok,
                       sum(output_token_count)                AS out_tok,
                       round(sum(cost_usd), 6)                AS cost_usd
                FROM annotated_events
                WHERE cost_usd IS NOT NULL
                  AND %s
                  AND %s
                GROUP BY rate_model
                ORDER BY cost_usd DESC
                """.formatted(TIME_FILTER, SOURCE_FILTER))
                .param("sinceEpoch", time.sinceEpoch())
                .param("untilEpoch", time.untilEpoch())
                .param("sourceTool", sourceTool(source))
                .query(CostByModel.class).list();
    }

    @GET
    @Path("/cost/by-trigger")
    public List<CostByTrigger> costByTrigger(
            @QueryParam("range") @DefaultValue("6h") String range,
            @QueryParam("source") @DefaultValue("codex") String source,
            @QueryParam("from") Long fromEpoch,
            @QueryParam("to") Long toEpoch) {
        TimeBounds time = timeBounds(range, fromEpoch, toEpoch);
        return db.sql("""
                SELECT COALESCE(trigger, 'unknown') AS trigger,
                       count(*)                     AS n,
                       round(sum(cost_usd), 6)      AS cost_usd
                FROM annotated_events
                WHERE cost_usd IS NOT NULL
                  AND %s
                  AND %s
                GROUP BY trigger
                ORDER BY cost_usd DESC
                """.formatted(TIME_FILTER, SOURCE_FILTER))
                .param("sinceEpoch", time.sinceEpoch())
                .param("untilEpoch", time.untilEpoch())
                .param("sourceTool", sourceTool(source))
                .query(CostByTrigger.class).list();
    }

    @GET
    @Path("/cost/by-model-trigger")
    public List<CostByModelTrigger> costByModelTrigger(
            @QueryParam("range") @DefaultValue("6h") String range,
            @QueryParam("source") @DefaultValue("codex") String source,
            @QueryParam("from") Long fromEpoch,
            @QueryParam("to") Long toEpoch) {
        TimeBounds time = timeBounds(range, fromEpoch, toEpoch);
        return db.sql("""
                SELECT COALESCE(rate_model, model, 'unknown') AS rate_model,
                       COALESCE(trigger, 'unknown')           AS trigger,
                       count(*)                               AS n,
                       round(sum(cost_usd), 6)                AS cost_usd
                FROM annotated_events
                WHERE cost_usd IS NOT NULL
                  AND %s
                  AND %s
                GROUP BY rate_model, trigger
                ORDER BY cost_usd DESC
                """.formatted(TIME_FILTER, SOURCE_FILTER))
                .param("sinceEpoch", time.sinceEpoch())
                .param("untilEpoch", time.untilEpoch())
                .param("sourceTool", sourceTool(source))
                .query(CostByModelTrigger.class).list();
    }

    @GET
    @Path("/tokens/by-model")
    public List<TokenByModel> tokensByModel(
            @QueryParam("range") @DefaultValue("6h") String range,
            @QueryParam("source") @DefaultValue("codex") String source,
            @QueryParam("from") Long fromEpoch,
            @QueryParam("to") Long toEpoch) {
        String sourceTool = sourceTool(source);
        String tokens = tokenAmountExpression(sourceTool);
        TimeBounds time = timeBounds(range, fromEpoch, toEpoch);
        return db.sql("""
                SELECT COALESCE(rate_model, model, 'unknown') AS rate_model,
                       count(*)                               AS n,
                       CAST(sum(%1$s) AS INTEGER)             AS tokens
                FROM annotated_events
                WHERE %1$s > 0
                  AND %2$s
                  AND %3$s
                GROUP BY rate_model
                ORDER BY tokens DESC
                """.formatted(tokens, TIME_FILTER, SOURCE_FILTER))
                .param("sinceEpoch", time.sinceEpoch())
                .param("untilEpoch", time.untilEpoch())
                .param("sourceTool", sourceTool)
                .query(TokenByModel.class).list();
    }

    @GET
    @Path("/tokens/by-trigger")
    public List<TokenByTrigger> tokensByTrigger(
            @QueryParam("range") @DefaultValue("6h") String range,
            @QueryParam("source") @DefaultValue("codex") String source,
            @QueryParam("from") Long fromEpoch,
            @QueryParam("to") Long toEpoch) {
        String sourceTool = sourceTool(source);
        String tokens = tokenAmountExpression(sourceTool);
        TimeBounds time = timeBounds(range, fromEpoch, toEpoch);
        return db.sql("""
                SELECT COALESCE(trigger, 'unknown') AS trigger,
                       count(*)                     AS n,
                       CAST(sum(%1$s) AS INTEGER)   AS tokens
                FROM annotated_events
                WHERE %1$s > 0
                  AND %2$s
                  AND %3$s
                GROUP BY trigger
                ORDER BY tokens DESC
                """.formatted(tokens, TIME_FILTER, SOURCE_FILTER))
                .param("sinceEpoch", time.sinceEpoch())
                .param("untilEpoch", time.untilEpoch())
                .param("sourceTool", sourceTool)
                .query(TokenByTrigger.class).list();
    }

    @GET
    @Path("/tokens/by-model-trigger")
    public List<TokenByModelTrigger> tokensByModelTrigger(
            @QueryParam("range") @DefaultValue("6h") String range,
            @QueryParam("source") @DefaultValue("codex") String source,
            @QueryParam("from") Long fromEpoch,
            @QueryParam("to") Long toEpoch) {
        String sourceTool = sourceTool(source);
        String tokens = tokenAmountExpression(sourceTool);
        TimeBounds time = timeBounds(range, fromEpoch, toEpoch);
        return db.sql("""
                SELECT COALESCE(rate_model, model, 'unknown') AS rate_model,
                       COALESCE(trigger, 'unknown')           AS trigger,
                       count(*)                               AS n,
                       CAST(sum(%1$s) AS INTEGER)             AS tokens
                FROM annotated_events
                WHERE %1$s > 0
                  AND %2$s
                  AND %3$s
                GROUP BY rate_model, trigger
                ORDER BY tokens DESC
                """.formatted(tokens, TIME_FILTER, SOURCE_FILTER))
                .param("sinceEpoch", time.sinceEpoch())
                .param("untilEpoch", time.untilEpoch())
                .param("sourceTool", sourceTool)
                .query(TokenByModelTrigger.class).list();
    }

    @GET
    @Path("/cost/by-trigger-timeseries")
    public List<CostSeriesPoint> costByTriggerOverTime(
            @QueryParam("range") @DefaultValue("6h") String range,
            @QueryParam("source") @DefaultValue("codex") String source,
            @QueryParam("grain") @DefaultValue("5m") String grain,
            @QueryParam("from") Long fromEpoch,
            @QueryParam("to") Long toEpoch) {
        TimeBounds time = timeBounds(range, fromEpoch, toEpoch);
        return db.sql("""
                SELECT %s                            AS bucket,
                       COALESCE(trigger, 'unknown')  AS series,
                       round(sum(cost_usd), 6)       AS cost_usd
                FROM annotated_events
                WHERE cost_usd IS NOT NULL
                  AND %s
                  AND %s
                GROUP BY bucket, series
                ORDER BY bucket
                """.formatted(BUCKET, TIME_FILTER, SOURCE_FILTER))
                .param("sinceEpoch", time.sinceEpoch())
                .param("untilEpoch", time.untilEpoch())
                .param("sourceTool", sourceTool(source))
                .param("grainSeconds", grain(grain).seconds())
                .query(CostSeriesPoint.class).list();
    }

    @GET
    @Path("/cost/by-type-timeseries")
    public List<CostSeriesPoint> costByTypeOverTime(
            @QueryParam("range") @DefaultValue("6h") String range,
            @QueryParam("source") @DefaultValue("codex") String source,
            @QueryParam("grain") @DefaultValue("5m") String grain,
            @QueryParam("from") Long fromEpoch,
            @QueryParam("to") Long toEpoch) {
        String sourceTool = sourceTool(source);
        TimeBounds time = timeBounds(range, fromEpoch, toEpoch);
        String sql = "claude".equals(sourceTool)
                ? claudeCostByTypeSql()
                : codexCostByTypeSql();
        return db.sql(sql)
                .param("sinceEpoch", time.sinceEpoch())
                .param("untilEpoch", time.untilEpoch())
                .param("sourceTool", sourceTool)
                .param("grainSeconds", grain(grain).seconds())
                .query(CostSeriesPoint.class).list();
    }

    @GET
    @Path("/tokens/by-type-timeseries")
    public List<TokenSeriesPoint> tokensByTypeOverTime(
            @QueryParam("range") @DefaultValue("6h") String range,
            @QueryParam("source") @DefaultValue("codex") String source,
            @QueryParam("grain") @DefaultValue("5m") String grain,
            @QueryParam("from") Long fromEpoch,
            @QueryParam("to") Long toEpoch) {
        String sourceTool = sourceTool(source);
        TimeBounds time = timeBounds(range, fromEpoch, toEpoch);
        String sql = "claude".equals(sourceTool)
                ? claudeTokensByTypeSql()
                : codexTokensByTypeSql();
        return db.sql(sql)
                .param("sinceEpoch", time.sinceEpoch())
                .param("untilEpoch", time.untilEpoch())
                .param("sourceTool", sourceTool)
                .param("grainSeconds", grain(grain).seconds())
                .query(TokenSeriesPoint.class).list();
    }

    @GET
    @Path("/tokens/by-trigger-timeseries")
    public List<TokenSeriesPoint> tokensByTriggerOverTime(
            @QueryParam("range") @DefaultValue("6h") String range,
            @QueryParam("source") @DefaultValue("codex") String source,
            @QueryParam("grain") @DefaultValue("5m") String grain,
            @QueryParam("from") Long fromEpoch,
            @QueryParam("to") Long toEpoch) {
        String sourceTool = sourceTool(source);
        String tokens = tokenAmountExpression(sourceTool);
        TimeBounds time = timeBounds(range, fromEpoch, toEpoch);
        return db.sql("""
                SELECT %1$s                           AS bucket,
                       COALESCE(trigger, 'unknown')   AS series,
                       CAST(sum(%2$s) AS INTEGER)     AS tokens
                FROM annotated_events
                WHERE %2$s > 0
                  AND %3$s
                  AND %4$s
                GROUP BY bucket, series
                ORDER BY bucket
                """.formatted(BUCKET, tokens, TIME_FILTER, SOURCE_FILTER))
                .param("sinceEpoch", time.sinceEpoch())
                .param("untilEpoch", time.untilEpoch())
                .param("sourceTool", sourceTool)
                .param("grainSeconds", grain(grain).seconds())
                .query(TokenSeriesPoint.class).list();
    }

    private static String codexTokensByTypeSql() {
        return """
                SELECT bucket, series, sum(tokens) AS tokens FROM (
                  SELECT %1$s AS bucket,
                         'uncached_input' AS series,
                         max(coalesce(input_token_count, 0) - coalesce(cached_input_token_count, 0), 0) AS tokens
                    FROM annotated_events WHERE input_token_count IS NOT NULL AND %2$s AND %3$s
                  UNION ALL
                  SELECT %1$s, 'cached_input', cached_input_token_count
                    FROM annotated_events WHERE cached_input_token_count IS NOT NULL AND %2$s AND %3$s
                  UNION ALL
                  SELECT %1$s, 'output', output_token_count
                    FROM annotated_events WHERE output_token_count IS NOT NULL AND %2$s AND %3$s
                )
                GROUP BY bucket, series
                HAVING tokens > 0
                ORDER BY bucket
                """.formatted(BUCKET, TIME_FILTER, SOURCE_FILTER);
    }

    private static String codexCostByTypeSql() {
        return """
                SELECT bucket, series, round(sum(cost_usd), 6) AS cost_usd FROM (
                  SELECT %1$s AS bucket, 'uncached_input' AS series, input_cost_usd AS cost_usd
                    FROM annotated_events WHERE input_cost_usd IS NOT NULL AND %2$s AND %3$s
                  UNION ALL
                  SELECT %1$s, 'cached_input', cached_input_cost_usd
                    FROM annotated_events WHERE cached_input_cost_usd IS NOT NULL AND %2$s AND %3$s
                  UNION ALL
                  SELECT %1$s, 'output', output_cost_usd
                    FROM annotated_events WHERE output_cost_usd IS NOT NULL AND %2$s AND %3$s
                )
                GROUP BY bucket, series
                HAVING cost_usd > 0
                ORDER BY bucket
                """.formatted(BUCKET, TIME_FILTER, SOURCE_FILTER);
    }

    private static String claudeCostByTypeSql() {
        return """
                SELECT bucket, series, round(sum(cost_usd), 6) AS cost_usd FROM (
                  SELECT %1$s AS bucket, 'input' AS series, input_cost_usd AS cost_usd
                    FROM annotated_events WHERE input_cost_usd IS NOT NULL AND %2$s AND %3$s
                  UNION ALL
                  SELECT %1$s, 'cache_creation', cache_creation_cost_usd
                    FROM annotated_events WHERE cache_creation_cost_usd IS NOT NULL AND %2$s AND %3$s
                  UNION ALL
                  SELECT %1$s, 'cache_read', cache_read_cost_usd
                    FROM annotated_events WHERE cache_read_cost_usd IS NOT NULL AND %2$s AND %3$s
                  UNION ALL
                  SELECT %1$s, 'output', output_cost_usd
                    FROM annotated_events WHERE output_cost_usd IS NOT NULL AND %2$s AND %3$s
                )
                GROUP BY bucket, series
                HAVING cost_usd > 0
                ORDER BY bucket
                """.formatted(BUCKET, TIME_FILTER, SOURCE_FILTER);
    }

    private static String claudeTokensByTypeSql() {
        return """
                SELECT bucket, series, sum(tokens) AS tokens FROM (
                  SELECT %1$s AS bucket,
                         'input' AS series,
                         CAST(COALESCE(json_extract(attributes_json, '$.input_tokens'), 0) AS INTEGER) AS tokens
                    FROM annotated_events WHERE attributes_json IS NOT NULL
                      AND json_extract(attributes_json, '$.input_tokens') IS NOT NULL
                      AND %2$s AND %3$s
                  UNION ALL
                  SELECT %1$s, 'cache_creation',
                         CAST(COALESCE(json_extract(attributes_json, '$.cache_creation_tokens'), 0) AS INTEGER)
                    FROM annotated_events WHERE attributes_json IS NOT NULL
                      AND json_extract(attributes_json, '$.cache_creation_tokens') IS NOT NULL
                      AND %2$s AND %3$s
                  UNION ALL
                  SELECT %1$s, 'cache_read',
                         CAST(COALESCE(json_extract(attributes_json, '$.cache_read_tokens'), 0) AS INTEGER)
                    FROM annotated_events WHERE attributes_json IS NOT NULL
                      AND json_extract(attributes_json, '$.cache_read_tokens') IS NOT NULL
                      AND %2$s AND %3$s
                  UNION ALL
                  SELECT %1$s, 'output', output_token_count
                    FROM annotated_events WHERE output_token_count IS NOT NULL AND %2$s AND %3$s
                )
                GROUP BY bucket, series
                HAVING tokens > 0
                ORDER BY bucket
                """.formatted(BUCKET, TIME_FILTER, SOURCE_FILTER);
    }

    private static String tokenAmountExpression(String sourceTool) {
        if ("claude".equals(sourceTool)) {
            return """
                    (CAST(coalesce(json_extract(attributes_json, '$.input_tokens'), 0) AS INTEGER)
                    + CAST(coalesce(json_extract(attributes_json, '$.cache_creation_tokens'), 0) AS INTEGER)
                    + CAST(coalesce(json_extract(attributes_json, '$.cache_read_tokens'), 0) AS INTEGER)
                    + coalesce(output_token_count, 0))
                    """;
        }
        return """
                (max(coalesce(input_token_count, 0) - coalesce(cached_input_token_count, 0), 0)
                + coalesce(cached_input_token_count, 0)
                + coalesce(output_token_count, 0))
                """;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static TimeBounds timeBounds(String range, Long fromEpoch, Long toEpoch) {
        long now = Instant.now().getEpochSecond();
        Long normalizedTo = normalizeEpoch(toEpoch);
        long until = normalizedTo == null ? now : normalizedTo;
        Long normalizedFrom = normalizeEpoch(fromEpoch);
        long since = normalizedFrom == null
                ? range(range).sinceEpoch(Instant.ofEpochSecond(until))
                : normalizedFrom;
        if (since >= until) {
            since = range(range).sinceEpoch(Instant.ofEpochSecond(until));
        }
        if (since >= until) {
            since = until - DEFAULT_RANGE.seconds();
        }
        return new TimeBounds(since, until);
    }

    private static Long normalizeEpoch(Long value) {
        if (value == null || value <= 0) {
            return null;
        }
        return value > 100_000_000_000L ? value / 1000 : value;
    }

    private static TimeRange range(String id) {
        return RANGES.getOrDefault(id, DEFAULT_RANGE);
    }

    private static TimeGrain grain(String id) {
        return GRAINS.getOrDefault(id, DEFAULT_GRAIN);
    }

    private static String sourceTool(String source) {
        return "claude".equals(source) ? "claude" : "codex";
    }
}
