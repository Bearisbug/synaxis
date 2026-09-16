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
     * Both ends must carry the exact same value; the handshake rejects anything else. Changing any
     * message layout raises it by one in the same change that updates the test vectors.
     */
    public static final int SCHEMA_VERSION = 1;

    private ProtocolSchema() {
    }
}
