package io.github.bearisbug.synaxis.game.client;

import io.github.bearisbug.synaxis.game.protocol.GameMessage;
import io.github.bearisbug.synaxis.game.protocol.GameMessages;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;
import io.github.bearisbug.synaxis.game.protocol.UnknownMessageException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code rts:game} channel of {@code API-M2-001} as a Fabric custom payload.
 * <p>
 * One payload type carries the whole channel in both directions: {@code msg_id} is scoped to the
 * channel, so {@link GameMessages} is the only dispatcher either end needs. The bytes on the wire
 * are produced by the shared {@code :protocol} jar and nothing else, which is what makes the
 * frozen vectors of §24 mean the same thing here as on the server.
 *
 * @param message the decoded message, or {@code null} when the sender used a {@code msg_id} this
 *                build does not define — §8 has the receiver drop such a packet with a warning
 *                instead of disconnecting, which is also what the server does (GameChannel)
 */
public record GamePayload(GameMessage message) implements CustomPacketPayload {

    private static final Logger LOGGER = LoggerFactory.getLogger("synaxis");

    /** The channel id; {@code rts} is the RTS plugin's namespace (REQ-M2-006). */
    public static final Type<GamePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("rts", "game"));

    /** The codec both registries are given; it delegates every byte to {@code :protocol}. */
    public static final StreamCodec<RegistryFriendlyByteBuf, GamePayload> CODEC =
            CustomPacketPayload.codec(GamePayload::encode, GamePayload::decode);

    /**
     * Reads one message off a payload buffer.
     *
     * @param buf the buffer holding exactly one payload
     * @return the decoded payload, carrying a {@code null} message if the id is unknown here
     */
    public static GamePayload decode(RegistryFriendlyByteBuf buf) {
        final byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        try {
            return new GamePayload(GameMessages.read(MsgBuf.reading(bytes)));
        } catch (UnknownMessageException e) {
            // An id this build does not define is a fact about the sender, not an offence: letting
            // it out of the decoder would drop the connection, which §8 forbids.
            LOGGER.warn("Dropped an unknown game message: msg_id=0x{}", Integer.toHexString(e.msgId()));
            return new GamePayload(null);
        }
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
