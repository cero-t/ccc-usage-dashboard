package cero.ninja.agent.codexusage.credit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RateCardTest {

    private final RateCard rateCard = new RateCard();

    @Test
    void mapsGpt56AliasAndVariants() {
        assertArrayEquals(new double[]{125, 12.5, 750}, rateCard.rateFor("gpt-5.6"));
        assertArrayEquals(new double[]{125, 12.5, 750}, rateCard.rateFor("gpt-5.6-sol"));
        assertArrayEquals(new double[]{62.5, 6.25, 375}, rateCard.rateFor("gpt-5.6-terra"));
        assertArrayEquals(new double[]{25, 2.5, 150}, rateCard.rateFor("gpt-5.6-luna"));
    }

    @Test
    void fallsBackToGpt56SolRates() {
        assertArrayEquals(new double[]{125, 12.5, 750}, rateCard.rateFor(null));
        assertArrayEquals(new double[]{125, 12.5, 750}, rateCard.rateFor("future-model"));
    }

    @Test
    void mapsCodexSparkToNoPublishedCreditRate() {
        assertArrayEquals(new double[]{0, 0, 0}, rateCard.rateFor("gpt-5.3-codex-spark"));
    }

    @Test
    void mapsGptImage2NamesToImageRates() {
        double[] imageRates = {200, 50, 750};
        assertArrayEquals(imageRates, rateCard.rateFor("gpt-image-2"));
        assertArrayEquals(imageRates, rateCard.rateFor("GPT-Image-2.0"));
        assertArrayEquals(imageRates, rateCard.rateFor("gpt-image-2-2026-04-21"));
    }

    @Test
    void computesGptImage2CreditsWithoutUsingUnknownModelFallback() {
        RateCard.Credits credits = rateCard.compute(
                "gpt-image-2",
                1_000_000L,
                200_000L,
                1_000_000L);

        assertEquals(160, credits.input());
        assertEquals(10, credits.cached());
        assertEquals(750, credits.output());
        assertEquals(920, credits.total());
    }
}
