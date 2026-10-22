package io.github.bearisbug.synaxis.client.play;

import io.github.bearisbug.synaxis.protocol.play.EncodedComponent;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

/**
 * The client end of a {@code component} on {@code synaxis:play} ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-002}):
 * the vanilla network format the host's {@code ComponentBytes} writes, read with the game's own component
 * stream codec, {@link ComponentSerialization#STREAM_CODEC}, the bounded one vanilla reads untrusted components
 * with. A translate key resolves against the mod's language files when the text is drawn, and a key they lack
 * shows its fallback (§6.4.5).
 */
public final class PlayComponents {

    private PlayComponents() {
    }

    /**
     * Decodes one component. Zero bytes is no text: empty text, without the codec ever seeing it
     * ({@code API-PLAY-002}).
     *
     * @param encoded    the component as {@code :protocol} carries it
     * @param registries the registries of the connection, which the codec reads through
     * @return the component
     * @throws IllegalArgumentException if the codec cannot read the bytes, or bytes are left after it
     */
    public static Component decode(EncodedComponent encoded, RegistryAccess registries) {
        if (encoded.isEmpty()) return Component.empty();
        final RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(encoded.bytes()), registries);
        final Component component;
        try {
            component = ComponentSerialization.STREAM_CODEC.decode(buf);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("the component codec could not read " + encoded + ": " + e, e);
        }
        if (buf.isReadable()) {
            throw new IllegalArgumentException(buf.readableBytes() + " bytes are left after the component " + encoded);
        }
        return component;
    }
}
