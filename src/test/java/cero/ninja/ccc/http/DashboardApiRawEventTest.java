package cero.ninja.ccc.http;

import cero.ninja.ccc.db.JdbcClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.is;

@QuarkusTest
@TestProfile(DashboardApiRawEventTest.Profile.class)
class DashboardApiRawEventTest {

    @Inject
    JdbcClient db;

    @BeforeEach
    void resetTables() {
        db.sql("DELETE FROM annotated_events").update();
        db.sql("DELETE FROM otel_log_records").update();
    }

    @Test
    void returnsTypedRawEvent() {
        db.sql("""
                INSERT INTO otel_log_records (id, record_json)
                VALUES (101, '{"event":"api_request"}')
                """).update();
        db.sql("""
                INSERT INTO annotated_events (id, source_log_id, event_name, thread_id)
                VALUES (201, 101, 'api_request', 'thread-1')
                """).update();

        given()
                .when().get("/api/events/201/raw")
                .then()
                .statusCode(200)
                .body("id", is(201))
                .body("sourceLogId", is(101))
                .body("eventName", is("api_request"))
                .body("threadId", is("thread-1"))
                .body("record.event", is("api_request"));
    }

    @Test
    void returnsBodylessNotFound() {
        given()
                .when().get("/api/events/999/raw")
                .then()
                .statusCode(404)
                .body(is(emptyOrNullString()));
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.datasource.jdbc.url",
                    "jdbc:sqlite:target/dashboard-api-raw-event-test.sqlite?journal_mode=WAL&busy_timeout=10000",
                    "quarkus.http.test-port", "0",
                    "quarkus.scheduler.enabled", "false");
        }
    }
}
