package io.github.bearisbug.synaxis.game.protocol.play;

/**
 * The {@code code} of {@link S2CActionResult}, {@code docs/DESIGN-PLAY.md} §9 and §14.
 * <p>
 * The wire values are the table of §9. The host judges an action as stale scene, rate limit, unknown action,
 * cooldown, then the plugin's handler ({@code API-PLAY-002}) and answers with the first that applies. None of
 * them disconnects anybody; every action is answered in band.
 */
public enum ActionResultCode {

    /** The action ran; the client restarts its local cooldown from the server's. */
    OK(0),
    /** The plugin's handler refused it; the reason is the plugin's translation key. */
    REJECTED(1),
    /** The slot is still cooling down; {@code cooldown_ms} is what is left. */
    COOLDOWN(2),
    /** The connection went over ten actions in the current whole second window. */
    RATE_LIMITED(3),
    /** The action belongs to an earlier scene. */
    STALE_SCENE(4),
    /** The action id is neither in the scene's action table nor a button of a panel. */
    UNKNOWN_ACTION(5);

    private final int wireId;

    ActionResultCode(int wireId) {
        this.wireId = wireId;
    }

    /**
     * The u8 this code is encoded as.
     *
     * @return the wire value
     */
    public int wireId() {
        return wireId;
    }

    /**
     * Decodes a {@code code} byte. A value this version does not know is shown as {@link #REJECTED} (§9), so a
     * newer server can add codes without the older client disconnecting.
     *
     * @param wireId the encoded value
     * @return the matching code, or {@link #REJECTED} for an unknown one
     */
    public static ActionResultCode fromWireId(int wireId) {
        return switch (wireId) {
            case 0 -> OK;
            case 2 -> COOLDOWN;
            case 3 -> RATE_LIMITED;
            case 4 -> STALE_SCENE;
            case 5 -> UNKNOWN_ACTION;
            default -> REJECTED;
        };
    }
}
