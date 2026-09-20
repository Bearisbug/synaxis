package io.github.bearisbug.synaxis.game.client;

import io.github.bearisbug.synaxis.game.protocol.GameMessage;
import io.github.bearisbug.synaxis.game.protocol.GameMessages;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The {@code rts:game} channel of {@code API-M2-001} as a Fabric custom payload.
 * <p>
 * One payload type carries the whole channel in both directions: {@code msg_id} is scoped to the
 * channel, so {@link GameMessages} is the only dispatcher either end needs. The bytes on the wire
 * are produced by the shared {@code :protocol} jar and nothing else, which is what makes the
 * frozen vectors of §24 mean the same thing here as on the server.
 *
 * @param message the decoded message
 */
public record GamePayload(GameMessage message) implements CustomPacketPayload {

    /** The channel id; {@code rts} is the RTS plugin's namespace (REQ-M2-006). */
    public static final Type<GamePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("rts", "game"));

    /** The codec both registries are given; it delegates every byte to {@code :protocol}. */
    public static final StreamCodec<RegistryFriendlyByteBuf, GamePayload> CODEC =
            CustomPacketPayload.codec(GamePayload::encode, GamePayload::decode);

    /**
     * Reads one message off a payload buffer.
     *
     * @param buf the buffer holding exactly one payload
     * @return the decoded payload
     */
    public static GamePayload decode(RegistryFriendlyByteBuf buf) {
        final byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return new GamePayload(GameMessages.read(MsgBuf.reading(bytes)));
    }

    /**
     * Writes this message into a payload buffer.
     *
     * @param buf the buffer to append to
     */
    public void encode(RegistryFriendlyByteBuf buf) {
        final MsgBuf out = MsgBuf.writing();
        message.write(out);
        buf.writeBytes(out.toByteArray());
    }

    @Override
    public Type<GamePayload> type() {
        return TYPE;
    }
}
