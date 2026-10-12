package io.github.bearisbug.synaxis.app.scene;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
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
    public static final int MAX_BYTES = 2_048;

    private ComponentBytes() {
    }

    /**
     * Encodes one component.
     *
     * @param component the component
     * @return its bytes, without a length prefix
     * @throws IllegalArgumentException if they are more than {@value #MAX_BYTES}
     */
    public static byte[] encode(Component component) {
        return NetworkBuffer.makeArray(NetworkBuffer.COMPONENT, component);
    }
}
