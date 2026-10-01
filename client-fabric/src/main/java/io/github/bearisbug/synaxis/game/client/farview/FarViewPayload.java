package io.github.bearisbug.synaxis.game.client.farview;

import io.github.bearisbug.synaxis.game.protocol.FarViewAnnouncement;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The far view announcement of {@code docs/DESIGN-M3.md} {@code API-M3-015} as a Fabric configuration
 * payload on {@value FarViewAnnouncement#CHANNEL}. The bytes are {@code :protocol}'s
 * {@link FarViewAnnouncement}, nothing of Minecraft's own around them.
 * <p>
 * Decoding never throws: a payload this build cannot read, an unknown {@code format_version}
 * included, is a payload without an announcement, which the connection takes as no announcement,
 * with one WARN line. Throwing here would end the connection, and the far view is optional.
 *
 * @param announcement the announcement, null when the payload is not one
 * @param problem      why it is not one, empty when it is
 */
public record FarViewPayload(@Nullable FarViewAnnouncement announcement, String problem) implements CustomPacketPayload {

    private static final Logger LOGGER = LoggerFactory.getLogger("synaxis");

    /** The channel id. */
    public static final Type<FarViewPayload> TYPE = new Type<>(Identifier.parse(FarViewAnnouncement.CHANNEL));

    /** The codec of the clientbound configuration registry. */
    public static final StreamCodec<FriendlyByteBuf, FarViewPayload> CODEC =
            CustomPacketPayload.codec(FarViewPayload::encode, FarViewPayload::decode);

    /**
     * A payload carrying an announcement.
     *
     * @param announcement the announcement
     */
    public FarViewPayload(FarViewAnnouncement announcement) {
        this(announcement, "");
    }

    /**
     * Reads one payload off a buffer, all of its readable bytes.
     *
     * @param buf the buffer holding exactly one payload
     * @return the payload, without an announcement when the bytes are not one
     */
    public static FarViewPayload decode(FriendlyByteBuf buf) {
        final byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return decode(bytes);
    }

    /**
     * Reads one payload.
     *
     * @param bytes the payload as it arrived
     * @return the payload, without an announcement when the bytes are not exactly one
     */
    public static FarViewPayload decode(byte[] bytes) {
        try {
            final MsgBuf in = MsgBuf.reading(bytes);
            final FarViewAnnouncement announcement = FarViewAnnouncement.read(in);
            if (in.remaining() != 0) return refused(in.remaining() + " bytes after the announcement");
            return new FarViewPayload(announcement);
        } catch (RuntimeException e) {
            return refused(String.valueOf(e.getMessage()));
        }
    }

    private static FarViewPayload refused(String problem) {
        LOGGER.warn("{}the far view announcement could not be read, taking it as none: {}", FarViewClient.PREFIX, problem);
        return new FarViewPayload(null, problem);
    }

    /**
     * Writes the announcement; a payload without one writes nothing.
     *
     * @param buf the buffer to append to
     */
    public void encode(FriendlyByteBuf buf) {
        if (announcement == null) return;
        final MsgBuf out = MsgBuf.writing();
        announcement.write(out);
        buf.writeBytes(out.toByteArray());
    }

    @Override
    public Type<FarViewPayload> type() {
        return TYPE;
    }
}
