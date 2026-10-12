package io.github.bearisbug.synaxis.game.protocol.play;

import io.github.bearisbug.synaxis.game.protocol.MalformedMessageException;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;

import java.util.Arrays;
import java.util.HexFormat;

/**
 * A text component as {@code synaxis:play} carries it ({@code API-PLAY-002}): a VarInt length and at most
 * {@value #MAX_BYTES} bytes.
 * <p>
 * The bytes are the vanilla network format the engine's {@code NetworkBuffer.COMPONENT} writes. This module
 * has no dependency, so it never looks inside them; the client decodes them with the game's own codec. Zero
 * bytes is no component, which only {@link S2CActionResult#reason()} uses.
 *
 * @param bytes the encoded component
 */
public record EncodedComponent(byte[] bytes) {

    /** The cap on an encoded component. */
    public static final int MAX_BYTES = 2048;

    /** No component: the reason of an action result that has none. */
    public static final EncodedComponent NONE = new EncodedComponent(new byte[0]);

    /**
     * Checks the cap and copies the bytes.
     */
    public EncodedComponent {
        if (bytes.length > MAX_BYTES) {
            throw new MalformedMessageException(
                    "component is %d bytes, over the cap of %d".formatted(bytes.length, MAX_BYTES));
        }
        bytes = bytes.clone();
    }

    /**
     * Reads a component.
     *
     * @param buf the reading buffer
     * @return the component
     * @throws MalformedMessageException if the length is negative, over the cap or past the end of the buffer
     */
    public static EncodedComponent read(MsgBuf buf) {
        final int length = buf.readVarInt();
        if (length < 0 || length > MAX_BYTES) {
            throw new MalformedMessageException("component length %d is outside [0, %d]".formatted(length, MAX_BYTES));
        }
        return new EncodedComponent(buf.readBytes(length));
    }

    /**
     * Writes the length and the bytes.
     *
     * @param buf the writing buffer
     */
    public void write(MsgBuf buf) {
        buf.writeVarInt(bytes.length);
        buf.writeBytes(bytes);
    }

    /**
     * Whether this is {@link #NONE}.
     *
     * @return true when there are no bytes
     */
    public boolean isEmpty() {
        return bytes.length == 0;
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EncodedComponent component && Arrays.equals(bytes, component.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "EncodedComponent[" + HexFormat.of().formatHex(bytes) + "]";
    }
}
