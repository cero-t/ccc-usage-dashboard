package cero.ninja.agent.codexusage.credit;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

/**
 * OpenAI Codex rate card — credits per 1M tokens as {input, cached, output}.
 * VERIFY against https://developers.openai.com/codex/pricing — it changes.
 *
 * <p>One request's credits are additive over three components:
 * <pre>
 *   uncached_input = max(0, input_tokens - cached_tokens) * input_rate  / 1e6
 *   cached_input   = cached_tokens                        * cached_rate / 1e6
 *   output         = output_tokens                        * output_rate / 1e6
 * </pre>
 * i.e. {@code input_token_count} is the full input (cached is a subset of it).
 *
 * <p>Credits are always computed at the standard rate. The Fast/priority service
 * tier is not surcharged: OTLP completion rows do not carry a reliable per-turn
 * service tier (see {@code dev_docs/codex-data-model.md}), so there is no
 * trustworthy signal to drive a surcharge.
 */
@ApplicationScoped
public class RateCard {

    private static final Map<String, double[]> RATES = Map.of(
            "gpt-5.5", new double[]{125, 12.5, 750},
            "gpt-5.4", new double[]{62.5, 6.25, 375},
            "gpt-5.4-mini", new double[]{18.75, 1.875, 113},
            "gpt-5.3-codex", new double[]{43.75, 4.375, 350},
            "gpt-5.2", new double[]{43.75, 4.375, 350}
    );

    // Unrecognized model is billed at GPT-5.3-Codex rates.
    private static final double[] FALLBACK = RATES.get("gpt-5.3-codex");

    public double[] rateFor(String model) {
        if (model == null) {
            return FALLBACK;
        }
        return RATES.getOrDefault(model.strip().toLowerCase(), FALLBACK);
    }

    public Credits compute(String model, Long inputTokens, Long cachedTokens, Long outputTokens) {
        double[] r = rateFor(model);
        long input = inputTokens == null ? 0 : inputTokens;
        long cached = cachedTokens == null ? 0 : cachedTokens;
        long output = outputTokens == null ? 0 : outputTokens;
        long uncached = Math.max(0, input - cached);

        double inputCredits = uncached * r[0] / 1_000_000.0;
        double cachedCredits = cached * r[1] / 1_000_000.0;
        double outputCredits = output * r[2] / 1_000_000.0;
        return new Credits(inputCredits, cachedCredits, outputCredits,
                inputCredits + cachedCredits + outputCredits);
    }

    public record Credits(double input, double cached, double output, double total) {}
}
