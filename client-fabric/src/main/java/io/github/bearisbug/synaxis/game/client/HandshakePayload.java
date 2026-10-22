package io.github.bearisbug.synaxis.game.client;

import io.github.bearisbug.synaxis.game.protocol.HandshakeMessage;
import io.github.bearisbug.synaxis.game.protocol.HandshakeMessages;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;
import io.github.bearisbug.synaxis.game.protocol.UnknownMessageException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code synaxis:handshake} channel of {@code API-M2-002} as a Fabric custom payload.
 * <p>
 * Its buffer type is the configuration phase's: the configuration registries hand out a plain
 * {@link FriendlyByteBuf}, because a connection being configured has no registries yet. Nothing in
 * this channel needs them — every byte is written by the shared {@code :protocol} jar.
 * <p>
 * The login phase form of the same negotiation carries no payload type at all: there the bytes
 * travel in a login plugin request, and {@link ClientHandshake#answerLoginQuery(byte[])} reads and
 * writes them directly.
 *
 * @param message the decoded message, or {@code null} when the sender used a {@code msg_id} this
 *                build does not define — §8 has the receiver drop such a packet with a warning
 */
public record HandshakePayload(HandshakeMessage message) implements CustomPacketPayload {

    private static final Logger LOGGER = LoggerFactory.getLogger("synaxis");

    /** The channel id; {@code synaxis} is the host's reserved namespace (REQ-M2-006). */
    public static final Type<HandshakePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("synaxis", "handshake"));

    /** The codec both configuration registries are given. */
    public static final StreamCodec<FriendlyByteBuf, HandshakePayload> CODEC =
            CustomPacketPayload.codec(HandshakePayload::encode, HandshakePayload::decode);

    /**
     * Reads one message off a payload buffer.
     *
     * @param buf the buffer holding exactly one payload
     * @return the decoded payload, carrying a {@code null} message if the id is unknown here
     */
    public static HandshakePayload decode(FriendlyByteBuf buf) {
        final byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        try {
            return new HandshakePayload(HandshakeMessages.read(MsgBuf.reading(bytes)));
        } catch (UnknownMessageException e) {
            LOGGER.warn("Dropped an unknown handshake message: msg_id=0x{}", Integer.toHexString(e.msgId()));
            return new HandshakePayload(null);
        }
    }

    /**
     * Writes this message into a payload buffer.
     *
     * @param buf the buffer to append to
     */
    public void encode(FriendlyByteBuf buf) {
        buf.writeBytes(message.encode());
    }

    @Override
    public Type<HandshakePayload> type() {
        return TYPE;
    }
}
