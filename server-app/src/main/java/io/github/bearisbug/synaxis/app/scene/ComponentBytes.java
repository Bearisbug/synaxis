package io.github.bearisbug.synaxis.app.scene;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.protocol.play.EncodedComponent;
import net.kyori.adventure.text.Component;

/**
 * The one place a {@link Component} becomes the bytes of a {@code component} on {@code synaxis:play}
 * ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-002}, §6.1): the vanilla network format the engine
 * writes with {@link NetworkBuffer#COMPONENT}, which the client reads with the vanilla component
 * stream codec. {@code :protocol} carries these bytes as opaque, behind a varint length.
 */
public final class ComponentBytes {

    /**
     * The most bytes one encoded component may take (§6.4.3).
     */
    public static final int MAX_BYTES = EncodedComponent.MAX_BYTES;

    private ComponentBytes() {
    }

    /**
     * Encodes one component.
     *
     * @param component the component
     * @return its bytes, without a length prefix
     * @throws IllegalArgumentException if they are more than {@value #MAX_BYTES}, or if
     *                                  {@link NetworkBuffer#COMPONENT} cannot encode the component
     * @throws IllegalStateException    if the component holds a {@code show_item} hover event whose data
     *                                  components need registries to be encoded
     */
    public static byte[] encode(Component component) {
        final byte[] bytes = NetworkBuffer.makeArray(NetworkBuffer.COMPONENT, component);
        if (bytes.length > MAX_BYTES)
            throw new IllegalArgumentException("a component encodes to at most " + MAX_BYTES + " bytes, this one to "
                    + bytes.length);
        return bytes;
    }
}
