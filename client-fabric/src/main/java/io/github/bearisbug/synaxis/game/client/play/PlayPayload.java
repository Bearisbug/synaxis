package io.github.bearisbug.synaxis.game.client.play;

import io.github.bearisbug.synaxis.game.protocol.MsgBuf;
import io.github.bearisbug.synaxis.game.protocol.UnknownMessageException;
import io.github.bearisbug.synaxis.game.protocol.play.PlayMessage;
import io.github.bearisbug.synaxis.game.protocol.play.PlayMessages;
import io.github.bearisbug.synaxis.game.protocol.play.PlayProtocol;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The host's scene layer channel {@value PlayProtocol#CHANNEL} of {@code docs/DESIGN-PLAY.md} {@code API-PLAY-002}
 * as a Fabric play payload, both directions. The bytes are {@code :protocol}'s {@link PlayMessages} and nothing
 * else, so the {@code play-vectors/} mean the same here as on the server; a component inside stays the opaque
 * bytes {@link PlayComponents} decodes.
 *
 * @param message the decoded message, or {@code null} when the server used a {@code msg_id} this build does
 *                not define: a newer server's message is dropped with a warning, never a disconnect
 *                ({@code API-PLAY-002})
 */
public record PlayPayload(@Nullable PlayMessage message) implements CustomPacketPayload {

    private static final Logger LOGGER = LoggerFactory.getLogger("synaxis");

    /** The channel id, {@code synaxis:play}. */
    public static final Type<PlayPayload> TYPE = new Type<>(Identifier.parse(PlayProtocol.CHANNEL));

    /** The codec both play registries are given; it delegates every byte to {@code :protocol}. */
    public static final StreamCodec<RegistryFriendlyByteBuf, PlayPayload> CODEC =
            CustomPacketPayload.codec(PlayPayload::encode, PlayPayload::decode);

    /**
     * Reads one message off a payload buffer.
     *
     * @param buf the buffer holding exactly one payload
     * @return the decoded payload, carrying a {@code null} message if the id is unknown here
     */
    public static PlayPayload decode(RegistryFriendlyByteBuf buf) {
        final byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        try {
            return new PlayPayload(PlayMessages.read(MsgBuf.reading(bytes)));
        } catch (UnknownMessageException e) {
            LOGGER.warn("Dropped an unknown play message: msg_id=0x{}", Integer.toHexString(e.msgId()));
            return new PlayPayload(null);
        }
    }

    /**
     * Writes this message into a payload buffer; a payload without one writes nothing.
     *
     * @param buf the buffer to append to
     */
    public void encode(RegistryFriendlyByteBuf buf) {
        if (message == null) return;
        final MsgBuf out = MsgBuf.writing();
        message.write(out);
        buf.writeBytes(out.toByteArray());
    }

    @Override
    public Type<PlayPayload> type() {
        return TYPE;
    }
}
