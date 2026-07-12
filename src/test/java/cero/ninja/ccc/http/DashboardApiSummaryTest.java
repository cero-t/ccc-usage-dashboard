package cero.ninja.ccc.http;

import cero.ninja.ccc.db.JdbcClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(DashboardApiSummaryTest.Profile.class)
class DashboardApiSummaryTest {

    @Inject
    JdbcClient db;

    @Inject
    DashboardApi api;

    @BeforeEach
    void resetTables() {
        db.sql("DELETE FROM cursor").update();
        db.sql("DELETE FROM annotated_events").update();
        db.sql("DELETE FROM usage_samples").update();
        db.sql("DELETE FROM otel_log_records").update();
    }

    @Test
    void summarizesFilteredEventsAndRawBacklogInCombinedQueries() {
        insertAnnotated(1, "codex", 1_700_000_100_000_000_000L,
                1.23456, 0.1234567, 100L, 20L, 30L);
        insertAnnotated(2, "codex", 1_700_000_200_000_000_000L,
                null, null, null, null, null);
        insertAnnotated(3, "claude", 1_700_000_300_000_000_000L,
                99.0, 99.0, 999L, 999L, 999L);
        insertAnnotated(4, "codex", 1_699_999_900_000_000_000L,
                88.0, 88.0, 888L, 888L, 888L);

        insertRaw(1, "2026-07-13 01:00:00");
        insertRaw(2, "2026-07-13 01:01:00");
        insertRaw(5, "2026-07-13 01:02:00");
        db.sql("INSERT INTO cursor (name, value) VALUES ('annotate_log_id', '2')").update();

        DashboardApi.Summary summary = api.summary(
                "6h", "codex", 1_700_000_000L, 1_700_001_000L);

        assertEquals(1.2346, summary.totalCredits(), 0.000001);
        assertEquals(0.123457, summary.totalCostUsd(), 0.000001);
        assertEquals(100, summary.totalInputTokens());
        assertEquals(20, summary.totalCachedInputTokens());
        assertEquals(30, summary.totalOutputTokens());
        assertEquals(2, summary.totalEvents());
        assertEquals(1, summary.eventsWithCredits());
        assertEquals(1, summary.eventsWithCost());
        assertEquals(3, summary.rawRecords());
        assertEquals(2, summary.annotateCursor());
        assertEquals(1, summary.backlog());
    }

    @Test
    void latestUsageReturnsOnlyAvailableWindowsWithActualDurations() {
        insertUsage(1, "primary", 300, 70.0, "2026-07-13 01:00:00");
        insertUsage(2, "secondary", 10080, 11.0, "2026-07-13 01:00:00");
        insertUsage(3, "primary", 10080, 13.0, "2026-07-13 01:01:00");
        insertUsage(4, "secondary", null, null, "2026-07-13 01:01:00");

        var latest = api.usageLatest();

        assertEquals(1, latest.size());
        assertEquals("primary", latest.getFirst().window());
        assertEquals(10080, latest.getFirst().windowDurationMins());
        assertEquals(13.0, latest.getFirst().usedPercent());
    }

    @Test
    void usageHistoryIncludesDurationForSlotTransitions() {
        insertUsage(1, "primary", 300, 70.0, "2023-11-14 22:14:00");
        insertUsage(2, "primary", 10080, 13.0, "2023-11-14 22:15:00");

        var history = api.usageHistory(
                "primary", "6h", "1m", 1_700_000_000L, 1_700_001_000L);

        assertEquals(2, history.size());
        assertEquals("primary", history.get(0).window());
        assertEquals(300, history.get(0).windowDurationMins());
        assertEquals(10080, history.get(1).windowDurationMins());
    }

    private void insertAnnotated(long sourceLogId, String sourceTool, long timeUnixNano,
                                 Double totalCredits, Double costUsd, Long inputTokens,
                                 Long cachedTokens, Long outputTokens) {
        db.sql("""
                INSERT INTO annotated_events (
                  source_log_id, source_tool, time_unix_nano, total_credits, cost_usd,
                  input_token_count, cached_input_token_count, output_token_count
                ) VALUES (
                  :source_log_id, :source_tool, :time_unix_nano, :total_credits, :cost_usd,
                  :input_tokens, :cached_tokens, :output_tokens
                )
                """)
                .param("source_log_id", sourceLogId)
                .param("source_tool", sourceTool)
                .param("time_unix_nano", timeUnixNano)
                .param("total_credits", totalCredits)
                .param("cost_usd", costUsd)
                .param("input_tokens", inputTokens)
                .param("cached_tokens", cachedTokens)
                .param("output_tokens", outputTokens)
                .update();
    }

    private void insertRaw(long id, String receivedAt) {
        db.sql("""
                INSERT INTO otel_log_records (id, received_at, record_json)
                VALUES (:id, :received_at, '{}')
                """)
                .param("id", id)
                .param("received_at", receivedAt)
                .update();
    }

    private void insertUsage(long id, String window, Integer durationMins,
                             Double usedPercent, String sampledAt) {
        db.sql("""
                INSERT INTO usage_samples (
                  id, sampled_at, plan_type, window, window_duration_mins,
                  used_percent, remaining_percent, resets_at
                ) VALUES (
                  :id, :sampled_at, 'prolite', :window, :duration_mins,
                  :used_percent, :remaining_percent, 1784487921
                )
                """)
                .param("id", id)
                .param("sampled_at", sampledAt)
                .param("window", window)
                .param("duration_mins", durationMins)
                .param("used_percent", usedPercent)
                .param("remaining_percent", usedPercent == null ? null : 100.0 - usedPercent)
                .update();
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.datasource.jdbc.url",
                    "jdbc:sqlite:target/dashboard-api-summary-test.sqlite?journal_mode=WAL&busy_timeout=10000",
                    "quarkus.http.test-port", "0",
                    "quarkus.scheduler.enabled", "false");
        }
    }
}
