package io.github.bearisbug.synaxis.app.scene;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The one encoding point of the components of {@code synaxis:play} ({@code docs/DESIGN-PLAY.md} §6.1,
 * {@code API-PLAY-002}): it writes the engine's vanilla network format, which reads back as the same
 * component, and refuses a component of more than 2,048 bytes (§6.4.3).
 */
class ComponentBytesTest {

    @Test
    void aComponentIsWrittenInTheNetworkFormatAndReadsBackAsItself() {
        final Component hint = Component.translatable("synaxis.action.cooldown", NamedTextColor.GOLD, Component.text("1"))
                .fallback("Ready again in %s s.");

        final byte[] bytes = ComponentBytes.encode(hint);

        assertEquals(hint, NetworkBuffer.wrap(bytes, 0, bytes.length).read(NetworkBuffer.COMPONENT));
    }

    @Test
    void aComponentOf2048BytesIsTakenAndOneByteMoreIsNot() {
        int length = 1;
        while (NetworkBuffer.makeArray(NetworkBuffer.COMPONENT, Component.text("x".repeat(length))).length < ComponentBytes.MAX_BYTES)
            length++;
        final Component largest = Component.text("x".repeat(length));
        final Component oneByteMore = Component.text("x".repeat(length + 1));

        assertEquals(ComponentBytes.MAX_BYTES, ComponentBytes.encode(largest).length);
        assertThrows(IllegalArgumentException.class, () -> ComponentBytes.encode(oneByteMore));
    }
}
