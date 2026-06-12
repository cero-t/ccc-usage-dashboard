package cero.ninja.agent.codexusage.credit;

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
