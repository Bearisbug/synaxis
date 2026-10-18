package io.github.bearisbug.synaxis.item.component;

import io.github.bearisbug.synaxis.component.DataComponents;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

public class TooltipDisplayTest {

    @Test
    void withHiddenHidesAndShowsAComponent() {
        final TooltipDisplay hidden = TooltipDisplay.EMPTY.withHidden(DataComponents.ENCHANTMENTS, true);
        assertEquals(Set.of(DataComponents.ENCHANTMENTS), hidden.hiddenComponents());
        assertEquals(Set.of(), hidden.withHidden(DataComponents.ENCHANTMENTS, false).hiddenComponents());

        assertSame(hidden, hidden.withHidden(DataComponents.ENCHANTMENTS, true));
        assertSame(TooltipDisplay.EMPTY, TooltipDisplay.EMPTY.withHidden(DataComponents.LORE, false));
    }

    @Test
    @SuppressWarnings("removal") // keeps the deprecated pair covered until it is removed
    void withShowsAndWithoutHides() {
        assertEquals(TooltipDisplay.EMPTY.withHidden(DataComponents.ENCHANTMENTS, true),
                TooltipDisplay.EMPTY.without(DataComponents.ENCHANTMENTS));
        assertEquals(TooltipDisplay.EMPTY,
                TooltipDisplay.EMPTY.withHidden(DataComponents.ENCHANTMENTS, true).with(DataComponents.ENCHANTMENTS));
    }

    @Test
    @SuppressWarnings("removal") // keeps the deprecated pair covered until it is removed
    void anUnchangedDisplayIsReturnedAsItIs() {
        assertSame(TooltipDisplay.EMPTY, TooltipDisplay.EMPTY.with(DataComponents.LORE));
        final TooltipDisplay hidden = new TooltipDisplay(false, Set.of(DataComponents.LORE));
        assertSame(hidden, hidden.without(DataComponents.LORE));
    }
}
