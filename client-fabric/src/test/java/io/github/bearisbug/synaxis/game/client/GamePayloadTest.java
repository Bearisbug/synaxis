package io.github.bearisbug.synaxis.game.client;

import io.github.bearisbug.synaxis.game.protocol.C2SCamera;
import io.github.bearisbug.synaxis.game.protocol.MalformedMessageException;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;
import io.github.bearisbug.synaxis.game.protocol.S2CUnitDespawn;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GamePayloadTest {

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    @Test
    void anOutboundMessageIsEncodedByTheSharedCodec() {
        final C2SCamera camera = new C2SCamera(12.5f, -40.25f, 24.0f, 90.0f);

        final RegistryFriendlyByteBuf buf = buffer();
        GamePayload.CODEC.encode(buf, new GamePayload(camera));

        final byte[] onTheWire = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), onTheWire);

        final MsgBuf expected = MsgBuf.writing();
        camera.write(expected);
        assertArrayEquals(expected.toByteArray(), onTheWire);
    }

    @Test
    void anInboundPayloadIsDecodedBackToTheSameMessage() {
        final S2CUnitDespawn despawn = new S2CUnitDespawn(List.of(7, 9));

        final RegistryFriendlyByteBuf buf = buffer();
        GamePayload.CODEC.encode(buf, new GamePayload(despawn));

        assertEquals(despawn, GamePayload.CODEC.decode(buf).message());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void aTruncatedPayloadIsRejectedByTheDecoder() {
        final RegistryFriendlyByteBuf buf = buffer();
        GamePayload.CODEC.encode(buf, new GamePayload(new C2SCamera(1.0f, 2.0f, 8.0f, 0.0f)));
        buf.writerIndex(buf.writerIndex() - 1);

        assertThrows(MalformedMessageException.class, () -> GamePayload.CODEC.decode(buf));
    }

    @Test
    void theChannelIdIsTheOneTheServerRoutesOn() {
        assertEquals("rts:game", GamePayload.TYPE.id().toString());
    }
}
