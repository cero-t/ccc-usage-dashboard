package cero.ninja.ccc.jobs;

import cero.ninja.ccc.db.JdbcClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
@TestProfile(UsageJobTest.Profile.class)
class UsageJobTest {

    @Inject
    JdbcClient db;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    UsageJob usageJob;

    @BeforeEach
    void resetTable() {
        db.sql("DELETE FROM usage_samples").update();
    }

    @Test
    void storesNormalFiveHourAndWeeklyWindowsWithDurations() throws Exception {
        int stored = usageJob.storeRateLimits(objectMapper.readTree("""
                {
                  "planType": "pro",
                  "primary": {"usedPercent": 12, "windowDurationMins": 300, "resetsAt": 1700000000},
                  "secondary": {"usedPercent": 34, "windowDurationMins": 10080, "resetsAt": 1700600000}
                }
                """));

        assertEquals(2, stored);
        List<Sample> samples = samples();
        assertEquals(2, samples.size());
        assertEquals(new Sample("primary", 300, 12.0, 88.0), samples.get(0));
        assertEquals(new Sample("secondary", 10080, 34.0, 66.0), samples.get(1));
    }

    @Test
    void storesMissingSecondaryAsUnavailableDuringWeeklyOnlyPeriod() throws Exception {
        int stored = usageJob.storeRateLimits(objectMapper.readTree("""
                {
                  "planType": "prolite",
                  "primary": {"usedPercent": 13, "windowDurationMins": 10080, "resetsAt": 1784487921},
                  "secondary": null
                }
                """));

        assertEquals(1, stored);
        List<Sample> samples = samples();
        assertEquals(2, samples.size());
        assertEquals(new Sample("primary", 10080, 13.0, 87.0), samples.get(0));
        assertEquals("secondary", samples.get(1).window());
        assertNull(samples.get(1).windowDurationMins());
        assertNull(samples.get(1).usedPercent());
        assertNull(samples.get(1).remainingPercent());
    }

    private List<Sample> samples() {
        return db.sql("""
                SELECT window, window_duration_mins, used_percent, remaining_percent
                FROM usage_samples
                ORDER BY id
                """).query(Sample.class).list();
    }

    private record Sample(
            String window,
            Integer windowDurationMins,
            Double usedPercent,
            Double remainingPercent) {}

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.datasource.jdbc.url",
                    "jdbc:sqlite:target/usage-job-test.sqlite?journal_mode=WAL&busy_timeout=10000",
                    "quarkus.http.test-port", "0",
                    "quarkus.scheduler.enabled", "false");
        }
    }
}
