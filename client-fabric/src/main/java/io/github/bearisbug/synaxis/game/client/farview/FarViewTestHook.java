package io.github.bearisbug.synaxis.game.client.farview;

/**
 * The switches the client gametests reach the far view's own code through, for the counter-proofs of
 * the container cases {@code TC-WORLD-119} and {@code TC-WORLD-120} ({@code docs/DESIGN-M3.md} §26
 * M3-S, 轨道 C 的四项容器规格 2): each takes one designed step away. As {@code DhTestHook} does for
 * the DH side; with nothing set, which is how the mod ships, every switch is the designed behaviour.
 */
public final class FarViewTestHook {

    /** False: {@code chunkRenderDistance} is not set from {@code farview.radius} ({@code counter-radius}). */
    public static volatile boolean radius = true;
    /** False: a Synaxis connection without a far view leaves DH its own folder, not {@code dh/off} ({@code counter-off}). */
    public static volatile boolean pinOff = true;
    /** False: a downloaded object is kept without its SHA-256 compared ({@code counter-sha}). */
    public static volatile boolean digest = true;
    /** False: a {@code .part} is asked for again without {@code Range} ({@code counter-range}). */
    public static volatile boolean range = true;

    private FarViewTestHook() {
    }

    /** Puts every switch back to the designed behaviour. */
    public static void reset() {
        radius = true;
        pinOff = true;
        digest = true;
        range = true;
    }
}
