package io.github.bearisbug.synaxis.world;

import io.github.bearisbug.synaxis.utils.WeightedList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntProviderTest {
    // The bounds of a weighted list are the lowest and the highest bound of its entries
    @Test
    void weightedBoundsSpanTheDistribution() {
        final IntProvider weighted = new IntProvider.Weighted(WeightedList.of(
                new WeightedList.Entry<>(new IntProvider.Uniform(7, 15), 1),
                new WeightedList.Entry<>(new IntProvider.Uniform(2, 4), 3)));

        assertEquals(2, weighted.minInclusive());
        assertEquals(15, weighted.maxInclusive());
    }

    @Test
    void anEmptyWeightedListHasNoBounds() {
        final IntProvider weighted = new IntProvider.Weighted(WeightedList.<IntProvider>of());

        assertThrows(IllegalStateException.class, weighted::minInclusive);
        assertThrows(IllegalStateException.class, weighted::maxInclusive);
    }

    @Test
    void aMaximumBelowTheMinimumIsRefused() {
        final String message = assertThrows(IllegalArgumentException.class, () -> new IntProvider.Uniform(7, 0)).getMessage();
        assertTrue(message.contains("(7)") && message.contains("(0)"), message);
        assertThrows(IllegalArgumentException.class, () -> new IntProvider.BiasedToBottom(7, 0));
        assertThrows(IllegalArgumentException.class, () -> new IntProvider.Clamped(new IntProvider.Constant(3), 7, 0));
        assertThrows(IllegalArgumentException.class, () -> new IntProvider.ClampedNormal(3, 1, 7, 0));
        assertThrows(IllegalArgumentException.class, () -> new IntProvider.Trapezoid(7, 0, 0));

        assertDoesNotThrow(() -> new IntProvider.Uniform(7, 7));
    }

    @Test
    void aTrapezoidPlateauFitsItsSpan() {
        assertThrows(IllegalArgumentException.class, () -> new IntProvider.Trapezoid(0, 7, 8));
        assertThrows(IllegalArgumentException.class, () -> new IntProvider.Trapezoid(0, 7, -1));

        assertDoesNotThrow(() -> new IntProvider.Trapezoid(0, 7, 7));
        assertDoesNotThrow(() -> new IntProvider.Trapezoid(-3, 3, 0));
    }
}
