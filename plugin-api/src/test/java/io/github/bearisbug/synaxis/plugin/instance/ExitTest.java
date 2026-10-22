package io.github.bearisbug.synaxis.plugin.instance;

import io.github.bearisbug.synaxis.plugin.DisconnectKeys;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The reason of a kick at the close is a component ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-006}); a
 * disconnect key alone is the component of that key, and there is always a reason.
 */
class ExitTest {

    @Test
    void aKickHasAReasonAndAKeyAloneIsTheTranslatableComponentOfThatKey() {
        assertEquals(new Exit.Kick(Component.translatable(DisconnectKeys.INSTANCE_UNAVAILABLE)),
                new Exit.Kick(DisconnectKeys.INSTANCE_UNAVAILABLE));
        assertThrows(NullPointerException.class, () -> new Exit.Kick((Component) null));
    }
}
