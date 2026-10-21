package io.github.bearisbug.synaxis.game.protocol;

/**
 * The wire protocol version axis, see {@code docs/DESIGN-M2.md} §8.
 * <p>
 * This package has no {@code package-info.java} and names no third party type, not even an
 * annotation: {@code module-info} declares zero {@code requires} because the same jar is shipped
 * inside the client mod (ADR-M2-008).
 */
public final class ProtocolSchema {

    /**
     * Both ends must carry the exact same value; the handshake rejects anything else.
     * <p>
     * It covers the messages of {@code synaxis:handshake} and {@code rts:game}
     * ({@link HandshakeMessage}, {@link GameMessage}). Changing any layout of theirs raises it by one
     * in the same change that updates the test vectors ({@code docs/DESIGN-M2.md} §8 and §24). So
     * does a new value of {@link OrderResultCode} or {@link UnitState}: a decoder that does not know
     * a value throws {@link MalformedMessageException}, which disconnects. {@code synaxis:play} has a
     * version of its own, {@link io.github.bearisbug.synaxis.game.protocol.play.PlayProtocol#version()},
     * and {@code synaxis:farview} has {@link FarViewAnnouncement#FORMAT_VERSION}; changing them leaves
     * this one as it is.
     */
    public static final int SCHEMA_VERSION = 1;

    private ProtocolSchema() {
    }
}
