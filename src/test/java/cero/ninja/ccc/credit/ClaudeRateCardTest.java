package cero.ninja.ccc.credit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClaudeRateCardTest {

    private final ClaudeRateCard rateCard = new ClaudeRateCard();

    @Test
    void computesFable5CostsWithFiveMinuteCacheWrites() {
        ClaudeRateCard.Costs costs = rateCard.compute(
                "claude-fable-5",
                1_000_000L,
                1_000_000L,
                1_000_000L,
                1_000_000L);

        assertEquals(10.0, costs.input());
        assertEquals(12.5, costs.cacheCreation());
        assertEquals(1.0, costs.cacheRead());
        assertEquals(50.0, costs.output());
        assertEquals(73.5, costs.total());
    }

    @Test
    void computesFable51CostsWithDiscountedCacheReads() {
        ClaudeRateCard.Costs costs = rateCard.compute(
                "claude-fable-5-1",
                1_000_000L,
                1_000_000L,
                1_000_000L,
                1_000_000L);

        assertEquals(10.0, costs.input());
        assertEquals(12.5, costs.cacheCreation());
        assertEquals(0.25, costs.cacheRead());
        assertEquals(50.0, costs.output());
        assertEquals(72.75, costs.total());
    }

    @Test
    void mapsFable51AndMythos51VariantsToFable51Rates() {
        ClaudeRateCard.Rates expected = rateCard.rateFor("claude-fable-5-1");

        assertEquals(0.25, expected.cacheReadPerMt());
        assertEquals(expected, rateCard.rateFor("claude-fable-5.1"));
        assertEquals(expected, rateCard.rateFor("claude-fable-5-1-20260901"));
        assertEquals(expected, rateCard.rateFor("claude-mythos-5-1"));
        assertEquals(expected, rateCard.rateFor("claude-mythos-5.1"));
    }

    @Test
    void keepsFable5AndMythos5CacheReadsAtStandardMultiplier() {
        assertEquals(1.0, rateCard.rateFor("claude-fable-5").cacheReadPerMt());
        assertEquals(1.0, rateCard.rateFor("claude-mythos-5").cacheReadPerMt());
    }

    @Test
    void computesOpus5CostsWithFiveMinuteCacheWrites() {
        ClaudeRateCard.Costs costs = rateCard.compute(
                "claude-opus-5",
                1_000_000L,
                1_000_000L,
                1_000_000L,
                1_000_000L);

        assertEquals(5.0, costs.input());
        assertEquals(6.25, costs.cacheCreation());
        assertEquals(0.5, costs.cacheRead());
        assertEquals(25.0, costs.output());
        assertEquals(36.75, costs.total());
    }

    @Test
    void computesOpus55CostsWithDiscountedCacheReads() {
        ClaudeRateCard.Costs costs = rateCard.compute(
                "claude-opus-5-5",
                1_000_000L,
                1_000_000L,
                1_000_000L,
                1_000_000L);

        assertEquals(4.0, costs.input());
        assertEquals(5.0, costs.cacheCreation());
        assertEquals(0.2, costs.cacheRead());
        assertEquals(20.0, costs.output());
        assertEquals(29.2, costs.total());
    }

    @Test
    void mapsOpus55VariantsWithoutAffectingOpus5() {
        ClaudeRateCard.Rates expected = rateCard.rateFor("claude-opus-5-5");

        assertEquals(expected, rateCard.rateFor("claude-opus-5.5"));
        assertEquals(5.0, rateCard.rateFor("claude-opus-5").inputPerMt());
    }

    @Test
    void computesOpus55FastModeCostsAtDoubleRates() {
        ClaudeRateCard.Costs costs = rateCard.compute(
                "claude-opus-5-5",
                "fast",
                1_000_000L,
                1_000_000L,
                1_000_000L,
                1_000_000L);

        assertEquals(8.0, costs.input());
        assertEquals(10.0, costs.cacheCreation());
        assertEquals(0.4, costs.cacheRead());
        assertEquals(40.0, costs.output());
        assertEquals(58.4, costs.total());
    }

    @Test
    void computesOpus5FastModeCostsAtDoubleRates() {
        ClaudeRateCard.Costs costs = rateCard.compute(
                "claude-opus-5",
                " FAST ",
                1_000_000L,
                1_000_000L,
                1_000_000L,
                1_000_000L);

        assertEquals(10.0, costs.input());
        assertEquals(12.5, costs.cacheCreation());
        assertEquals(1.0, costs.cacheRead());
        assertEquals(50.0, costs.output());
        assertEquals(73.5, costs.total());
    }

    @Test
    void appliesFastModeToOpus48() {
        assertEquals(10.0, rateCard.rateFor("claude-opus-4-8", "fast").inputPerMt());
        assertEquals(50.0, rateCard.rateFor("claude-opus-4-8", "fast").outputPerMt());
    }

    @Test
    void keepsStandardRatesWhenSpeedIsNotFast() {
        ClaudeRateCard.Rates standard = rateCard.rateFor("claude-opus-5-5");

        assertEquals(standard, rateCard.rateFor("claude-opus-5-5", null));
        assertEquals(standard, rateCard.rateFor("claude-opus-5-5", "normal"));
        assertEquals(standard, rateCard.rateFor("claude-opus-5-5", "standard"));
    }

    @Test
    void ignoresFastSpeedOnModelsWithoutFastMode() {
        assertEquals(rateCard.rateFor("claude-opus-4-6"), rateCard.rateFor("claude-opus-4-6", "fast"));
        assertEquals(rateCard.rateFor("claude-opus-4-7"), rateCard.rateFor("claude-opus-4-7", "fast"));
        assertEquals(rateCard.rateFor("claude-sonnet-5"), rateCard.rateFor("claude-sonnet-5", "fast"));
        assertEquals(rateCard.rateFor("claude-fable-5-1"), rateCard.rateFor("claude-fable-5-1", "fast"));
        assertEquals(rateCard.rateFor("claude-haiku-5-5"), rateCard.rateFor("claude-haiku-5-5", "fast"));
    }

    @Test
    void computesSonnet5StandardCosts() {
        ClaudeRateCard.Costs costs = rateCard.compute(
                "claude-sonnet-5",
                1_000_000L,
                1_000_000L,
                1_000_000L,
                1_000_000L);

        assertEquals(2.0, costs.input());
        assertEquals(2.5, costs.cacheCreation());
        assertEquals(0.2, costs.cacheRead());
        assertEquals(10.0, costs.output());
        assertEquals(14.7, costs.total());
    }

    @Test
    void computesSonnet55CostsWithDiscountedCacheReads() {
        ClaudeRateCard.Costs costs = rateCard.compute(
                "claude-sonnet-5-5",
                1_000_000L,
                1_000_000L,
                1_000_000L,
                1_000_000L);

        assertEquals(2.0, costs.input());
        assertEquals(2.5, costs.cacheCreation());
        assertEquals(0.1, costs.cacheRead());
        assertEquals(10.0, costs.output());
        assertEquals(14.6, costs.total());
    }

    @Test
    void mapsSonnet55VariantsWithoutAffectingSonnet5() {
        ClaudeRateCard.Rates expected = rateCard.rateFor("claude-sonnet-5-5");

        assertEquals(0.1, expected.cacheReadPerMt());
        assertEquals(expected, rateCard.rateFor("claude-sonnet-5.5"));
        assertEquals(0.2, rateCard.rateFor("claude-sonnet-5").cacheReadPerMt());
    }

    @Test
    void computesHaiku55ShortPromptCosts() {
        // Exactly 100,000 prompt tokens is still the short-prompt row.
        ClaudeRateCard.Costs costs = rateCard.compute(
                "claude-haiku-5-5",
                50_000L,
                20_000L,
                30_000L,
                10_000L);

        assertEquals(0.005, costs.input());
        assertEquals(0.0025, costs.cacheCreation());
        assertEquals(0.0003, costs.cacheRead());
        assertEquals(0.005, costs.output());
        assertEquals(0.0128, costs.total());
    }

    @Test
    void computesHaiku55LongPromptCostsAtHigherRates() {
        ClaudeRateCard.Costs costs = rateCard.compute(
                "claude-haiku-5-5",
                1_000_000L,
                0L,
                0L,
                1_000_000L);

        assertEquals(0.5, costs.input());
        assertEquals(2.5, costs.output());
        assertEquals(3.0, costs.total());
    }

    @Test
    void countsCacheTokensTowardHaiku55PromptLength() {
        // No uncached input, but 200k cache reads put the prompt over 100k tokens.
        ClaudeRateCard.Costs costs = rateCard.compute(
                "claude-haiku-5-5",
                0L,
                0L,
                200_000L,
                0L);

        assertEquals(0.01, costs.cacheRead());
        assertEquals(0.01, costs.total());
    }

    @Test
    void selectsHaiku55RowByPromptLengthThreshold() {
        assertEquals(0.10, rateCard.rateFor("claude-haiku-5-5", null, 100_000).inputPerMt());
        assertEquals(0.50, rateCard.rateFor("claude-haiku-5.5", null, 100_001).inputPerMt());
        assertEquals(0.10, rateCard.rateFor("claude-haiku-5-5").inputPerMt());
    }

    @Test
    void mapsVersionedHaiku45ModelIds() {
        ClaudeRateCard.Costs costs = rateCard.compute(
                "claude-haiku-4-5-20251001",
                1_000_000L,
                1_000_000L,
                1_000_000L,
                1_000_000L);

        assertEquals(1.0, costs.input());
        assertEquals(1.25, costs.cacheCreation());
        assertEquals(0.10, costs.cacheRead());
        assertEquals(5.0, costs.output());
        assertEquals(7.35, costs.total());
    }
}
