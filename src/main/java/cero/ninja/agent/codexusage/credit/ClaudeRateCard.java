package cero.ninja.agent.codexusage.credit;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Claude API USD rate card per 1M tokens.
 *
 * <p>Claude Code exposes {@code cache_creation_tokens} without a TTL split in
 * the local OTLP record. The dashboard treats those writes as 5-minute cache
 * writes, matching the observed Claude Code billing behavior.
 */
@ApplicationScoped
public class ClaudeRateCard {

    private static final Rates FABLE_5 = new Rates(10.0, 12.50, 1.0, 50.0);
    private static final Rates OPUS_4_LATEST = new Rates(5.0, 6.25, 0.50, 25.0);
    private static final Rates OPUS_4_LEGACY = new Rates(15.0, 18.75, 1.50, 75.0);
    private static final Rates SONNET_4 = new Rates(3.0, 3.75, 0.30, 15.0);
    private static final Rates HAIKU_4_5 = new Rates(1.0, 1.25, 0.10, 5.0);
    private static final Rates HAIKU_3_5 = new Rates(0.80, 1.0, 0.08, 4.0);

    public Costs compute(String model, Long inputTokens, Long cacheCreationTokens,
                         Long cacheReadTokens, Long outputTokens) {
        Rates rates = rateFor(model);
        double input = usd(inputTokens, rates.inputPerMt());
        double cacheCreation = usd(cacheCreationTokens, rates.cacheWrite5mPerMt());
        double cacheRead = usd(cacheReadTokens, rates.cacheReadPerMt());
        double output = usd(outputTokens, rates.outputPerMt());
        return new Costs(input, cacheCreation, cacheRead, output,
                round(input + cacheCreation + cacheRead + output));
    }

    public Rates rateFor(String model) {
        if (model == null || model.isBlank()) {
            return FABLE_5;
        }
        String m = model.strip().toLowerCase();
        if (m.contains("fable-5") || m.contains("mythos-5")) {
            return FABLE_5;
        }
        if (m.contains("opus-4-8") || m.contains("opus-4.8")
                || m.contains("opus-4-7") || m.contains("opus-4.7")
                || m.contains("opus-4-6") || m.contains("opus-4.6")
                || m.contains("opus-4-5") || m.contains("opus-4.5")) {
            return OPUS_4_LATEST;
        }
        if (m.contains("opus-4-1") || m.contains("opus-4.1") || m.contains("opus-4")) {
            return OPUS_4_LEGACY;
        }
        if (m.contains("sonnet-4")) {
            return SONNET_4;
        }
        if (m.contains("haiku-4-5") || m.contains("haiku-4.5")) {
            return HAIKU_4_5;
        }
        if (m.contains("haiku-3-5") || m.contains("haiku-3.5")) {
            return HAIKU_3_5;
        }
        return FABLE_5;
    }

    private static double usd(Long tokens, double usdPerMt) {
        long t = tokens == null ? 0 : tokens;
        return round(t * usdPerMt / 1_000_000.0);
    }

    private static double round(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }

    public record Rates(double inputPerMt, double cacheWrite5mPerMt,
                        double cacheReadPerMt, double outputPerMt) {}

    public record Costs(double input, double cacheCreation, double cacheRead,
                        double output, double total) {}
}
