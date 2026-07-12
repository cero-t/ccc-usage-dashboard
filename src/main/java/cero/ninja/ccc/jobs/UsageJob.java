package cero.ninja.ccc.jobs;

import cero.ninja.ccc.db.JdbcClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Job B. Polls Codex's current rate-limit usage and appends a snapshot per
 * window to {@code usage_samples}. Usage is NOT in any SQLite DB: it is read by
 * launching {@code codex app-server --listen stdio://} and issuing the
 * {@code account/rateLimits/read} request over stdio. Completely independent of
 * the annotate job — a DB lock there has no bearing here, and a CLI timeout here
 * has no bearing there.
 */
@ApplicationScoped
public class UsageJob {

    private static final Logger LOG = Logger.getLogger(UsageJob.class);

    private static final String INSERT_USAGE = """
            INSERT INTO usage_samples (
              plan_type, window, window_duration_mins, used_percent, remaining_percent, resets_at
            ) VALUES (
              :plan_type, :window, :window_duration_mins, :used_percent, :remaining_percent, :resets_at
            )
            """;

    @Inject
    JdbcClient db;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "codex.bin", defaultValue = "codex")
    String codexBin;

    @ConfigProperty(name = "ccc-usage-dashboard.codex.enabled", defaultValue = "true")
    boolean codexEnabled;

    @ConfigProperty(name = "codex.usage.timeout-ms", defaultValue = "15000")
    long timeoutMs;

    @Scheduled(every = "{ccc-usage-dashboard.usage.every}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void run() {
        if (!codexEnabled) {
            LOG.debug("Codex support disabled; skipping usage poll");
            return;
        }
        JsonNode result;
        try {
            result = readRateLimits();
        } catch (Exception e) {
            LOG.warnf("usage poll failed (best-effort, retry next pass): %s", e.getMessage());
            return;
        }
        JsonNode snap = result.path("rateLimits");
        if (snap.isMissingNode() || snap.isNull()) {
            LOG.debug("usage poll returned no rateLimits snapshot");
            return;
        }
        String plan = textOrNull(snap.path("planType"));
        int stored = storeRateLimits(snap);
        if (stored > 0) {
            LOG.infof("usage: %d window sample(s) stored (plan=%s)", stored, plan);
        }
    }

    int storeRateLimits(JsonNode snap) {
        String plan = textOrNull(snap.path("planType"));
        return storeWindow(plan, "primary", snap.path("primary"))
                + storeWindow(plan, "secondary", snap.path("secondary"));
    }

    private int storeWindow(String plan, String window, JsonNode node) {
        boolean available = !node.isMissingNode() && !node.isNull();
        JsonNode used = available ? node.path("usedPercent") : null;
        JsonNode duration = available ? node.path("windowDurationMins") : null;
        JsonNode reset = available ? node.path("resetsAt") : null;
        Double usedPercent = used == null || used.isMissingNode() || used.isNull()
                ? null : used.asDouble();
        db.sql(INSERT_USAGE)
                .param("plan_type", plan)
                .param("window", window)
                .param("window_duration_mins",
                        duration == null || duration.isMissingNode() || duration.isNull() ? null : duration.asInt())
                .param("used_percent", usedPercent)
                .param("remaining_percent", usedPercent == null ? null : 100.0 - usedPercent)
                .param("resets_at", reset == null || reset.isMissingNode() || reset.isNull() ? null : reset.asLong())
                .update();
        return usedPercent == null ? 0 : 1;
    }

    /** Returns the {@code result} object of the {@code rateLimits/read} (id=1) response. */
    private JsonNode readRateLimits() throws Exception {
        List<Map<String, Object>> messages = List.of(
                Map.of(
                        "method", "initialize",
                        "id", 0,
                        "params", Map.of(
                                "clientInfo", Map.of(
                                        "name", "ccc-usage-dashboard",
                                        "title", "ccc-usage-dashboard",
                                        "version", "0.1.0"),
                                "capabilities", Map.of("experimentalApi", true))),
                Map.of("method", "initialized", "params", Map.of()),
                Map.of("method", "account/rateLimits/read", "id", 1, "params", Map.of()));

        ProcessBuilder pb = new ProcessBuilder(codexBin, "app-server", "--listen", "stdio://");
        // Discard the child's stderr. We only consume the JSON-RPC reply on stdout; an
        // undrained stderr pipe can fill its OS buffer and stall the child mid-response,
        // which would trip reader.get(timeout) every pass.
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process proc = pb.start();
        try (BufferedWriter stdin = new BufferedWriter(
                new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader stdout = new BufferedReader(
                     new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            for (Map<String, Object> message : messages) {
                stdin.write(objectMapper.writeValueAsString(message));
                stdin.write("\n");
            }
            stdin.flush();

            CompletableFuture<JsonNode> reader = CompletableFuture.supplyAsync(() -> readResponse(stdout));
            return reader.get(timeoutMs, TimeUnit.MILLISECONDS);
        } finally {
            proc.destroyForcibly();
        }
    }

    private JsonNode readResponse(BufferedReader stdout) {
        try {
            String line;
            while ((line = stdout.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node;
                try {
                    node = objectMapper.readTree(line);
                } catch (Exception ignored) {
                    continue; // non-JSON log noise on stdout
                }
                JsonNode id = node.path("id");
                if (!id.isInt() || id.asInt() != 1) {
                    continue;
                }
                if (node.has("error")) {
                    throw new RuntimeException("app-server rateLimits error: " + node.path("error"));
                }
                return node.path("result");
            }
            throw new RuntimeException("app-server closed stdout before rateLimits response");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String textOrNull(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
