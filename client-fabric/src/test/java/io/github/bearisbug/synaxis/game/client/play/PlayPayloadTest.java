package io.github.bearisbug.synaxis.game.client.play;

import io.github.bearisbug.synaxis.game.protocol.MalformedMessageException;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;
import io.github.bearisbug.synaxis.game.protocol.play.ActionResultCode;
import io.github.bearisbug.synaxis.game.protocol.play.C2SAction;
import io.github.bearisbug.synaxis.game.protocol.play.C2SSceneReady;
import io.github.bearisbug.synaxis.game.protocol.play.EncodedComponent;
import io.github.bearisbug.synaxis.game.protocol.play.PlayMessage;
import io.github.bearisbug.synaxis.game.protocol.play.S2CActionResult;
import io.github.bearisbug.synaxis.game.protocol.play.S2CHud;
import io.github.bearisbug.synaxis.game.protocol.play.S2CScene;
import io.github.bearisbug.synaxis.game.protocol.play.Widget;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code synaxis:play} as a Fabric payload ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-002}): the bytes are
 * {@code :protocol}'s in both directions; a malformed message from the server throws out of the decoder, which
 * Fabric answers by dropping the connection; a {@code msg_id} version 1 does not define is dropped with a warning
 * instead.
 */
class PlayPayloadTest {

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    @Test
    void theTwoUpwardMessagesGoOutAsTheSharedCodecWritesThem() {
        for (final PlayMessage message : List.<PlayMessage>of(new C2SSceneReady(3),
                new C2SAction(3, 7, "probe:queue", new C2SAction.OfBlock(1, -2, 3)))) {
            final RegistryFriendlyByteBuf buf = buffer();
            PlayPayload.CODEC.encode(buf, new PlayPayload(message));
            final byte[] onTheWire = new byte[buf.readableBytes()];
            buf.readBytes(onTheWire);

            final MsgBuf expected = MsgBuf.writing();
            message.write(expected);
            assertArrayEquals(expected.toByteArray(), onTheWire, message.toString());
        }
    }

    @Test
    void theThreeDownwardMessagesComeBackAsTheySent() {
        for (final PlayMessage message : List.<PlayMessage>of(
                new S2CScene(2, "probe:round", EncodedComponent.NONE, 0, 0, List.of()),
                new S2CHud(List.of(new S2CHud.SetWidget("probe", "timer", Widget.timer(Widget.TOP_CENTER, 0,
                        EncodedComponent.NONE, 45_000, true)), new S2CHud.ClearOwner("probe"))),
                new S2CActionResult(7, ActionResultCode.COOLDOWN, EncodedComponent.NONE, 1_500))) {
            final RegistryFriendlyByteBuf buf = buffer();
            PlayPayload.CODEC.encode(buf, new PlayPayload(message));

            assertEquals(message, PlayPayload.CODEC.decode(buf).message());
            assertEquals(0, buf.readableBytes());
        }
    }

    @Test
    void aTruncatedMessageThrowsOutOfTheDecoder() {
        final RegistryFriendlyByteBuf buf = buffer();
        PlayPayload.CODEC.encode(buf, new PlayPayload(new S2CActionResult(7, ActionResultCode.OK, EncodedComponent.NONE, 2000)));
        buf.writerIndex(buf.writerIndex() - 1);

        assertThrows(MalformedMessageException.class, () -> PlayPayload.CODEC.decode(buf));
    }

    @Test
    void aMessageBrokenInsideThrowsToo() {
        // One op numbered 9, which version 1 does not define
        final MsgBuf out = MsgBuf.writing();
        out.writeVarInt(S2CHud.MSG_ID);
        out.writeVarInt(1);
        out.writeUnsignedByte(9);
        out.writeString("probe");
        out.writeString("timer");
        final RegistryFriendlyByteBuf buf = buffer();
        buf.writeBytes(out.toByteArray());

        assertThrows(MalformedMessageException.class, () -> PlayPayload.CODEC.decode(buf));
    }

    @Test
    void aMessageOfALaterVersionIsDroppedRatherThanBreakingTheConnection() {
        final MsgBuf notice = MsgBuf.writing();
        notice.writeVarInt(0x83);
        notice.writeUnsignedByte(0);
        final RegistryFriendlyByteBuf buf = buffer();
        buf.writeBytes(notice.toByteArray());

        assertNull(PlayPayload.CODEC.decode(buf).message());
    }

    @Test
    void theChannelIdIsTheOneTheHostRoutesOn() {
        assertEquals("synaxis:play", PlayPayload.TYPE.id().toString());
    }
}
