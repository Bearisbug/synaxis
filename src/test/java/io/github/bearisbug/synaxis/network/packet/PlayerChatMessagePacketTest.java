package io.github.bearisbug.synaxis.network.packet;

import io.github.bearisbug.synaxis.crypto.FilterMask;
import io.github.bearisbug.synaxis.crypto.LastSeenMessages;
import io.github.bearisbug.synaxis.crypto.SignedMessageBody;
import io.github.bearisbug.synaxis.network.packet.server.play.PlayerChatMessagePacket;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.BitSet;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The translation of a chat message on the write thread hands the operator only the components the
 * packet has: a signed chat message often has no unsigned content and no target.
 */
public class PlayerChatMessagePacketTest {

    @Test
    public void theOperatorIsNotHandedTheComponentsThePacketDoesNotHave() {
        final PlayerChatMessagePacket packet = new PlayerChatMessagePacket(0, UUID.randomUUID(), 0, null,
                new SignedMessageBody.Packed("hello", Instant.EPOCH, 0, LastSeenMessages.Packed.EMPTY),
                null, new FilterMask(FilterMask.Type.PASS_THROUGH, new BitSet()),
                0, Component.text("chat"), null);

        final PlayerChatMessagePacket translated = assertInstanceOf(PlayerChatMessagePacket.class,
                packet.copyWithOperator(component -> Objects.requireNonNull(component).append(Component.text("!"))));

        assertEquals(Component.text("chat").append(Component.text("!")), translated.msgTypeName());
        assertNull(translated.unsignedContent());
        assertNull(translated.msgTypeTarget());
    }
}
