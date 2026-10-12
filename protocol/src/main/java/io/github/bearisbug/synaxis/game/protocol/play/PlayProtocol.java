package io.github.bearisbug.synaxis.game.protocol.play;

/**
 * The host's scene layer channel {@value #CHANNEL} of {@code docs/DESIGN-PLAY.md} {@code API-PLAY-002}: its
 * name, its version and the limits shared by the messages of this package.
 * <p>
 * The channel has a version of its own, apart from the {@code schema_version} of the handshake, after the
 * precedent of {@code synaxis:farview}: the frozen vectors under {@code play-vectors/} are bound to it, and from
 * W1 on the handshake negotiates it per channel (§6.4.1).
 */
public final class PlayProtocol {

    /** The plugin message channel, one whole string for the translation key gate. */
    public static final String CHANNEL = "synaxis:play";

    /** The cap on an encoded scene id, action id or widget owner. */
    public static final int MAX_ID_BYTES = 64;

    /** The cap on {@code cooldown_ms}, ten minutes. */
    public static final int MAX_COOLDOWN_MS = 600_000;

    /**
     * The cap the host keeps every {@link S2CScene} and every {@link S2CHud} within, checked when the host
     * encodes; the decoder does not check it.
     */
    public static final int MAX_MESSAGE_BYTES = 8192;

    /**
     * The cap the host keeps every encoded {@link Widget} within, checked when a plugin sets it; the decoder
     * does not check it.
     */
    public static final int MAX_WIDGET_BYTES = 4096;

    private PlayProtocol() {
    }

    /**
     * The version of this channel the jar speaks: 1 is the five messages and four widget kinds of S1, 2 adds
     * the messages and kinds of W2. It is a method and not a constant, so that a dependant does not inline the
     * value and the ABI gate does not report every bump as a changed constant (§6.4.7).
     *
     * @return the channel version
     */
    public static int version() {
        return 1;
    }
}
