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
