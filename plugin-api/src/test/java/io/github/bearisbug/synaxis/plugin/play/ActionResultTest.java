package io.github.bearisbug.synaxis.plugin.play;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The reason of a refused action is a component ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-005}), so it can
 * carry a fallback and arguments; a translate key alone is the component of that key.
 */
class ActionResultTest {

    @Test
    void aKeyAloneIsTheTranslatableComponentOfThatKey() {
        assertEquals(new ActionResult.Rejected(Component.translatable("rush.no")), new ActionResult.Rejected("rush.no"));
        final Component withArguments = Component.translatable("rush.no", Component.text(3)).fallback("Not now, %s s left");
        assertEquals(withArguments, new ActionResult.Rejected(withArguments).reason());
    }
}
