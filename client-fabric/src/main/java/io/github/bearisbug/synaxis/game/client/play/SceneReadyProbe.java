package io.github.bearisbug.synaxis.game.client.play;

import io.github.bearisbug.synaxis.game.protocol.play.C2SSceneReady;
import io.github.bearisbug.synaxis.game.protocol.play.PlayMessage;
import java.util.function.Consumer;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import org.jspecify.annotations.Nullable;

/**
 * When a scene is ready ({@code docs/DESIGN-PLAY.md} §6.4.3): one {@code C2S_SCENE_READY} per {@code S2C_SCENE}
 * at most, sent from the first client tick at which all three hold: ① the scene is applied, ② the client is in a
 * level with a player and the vanilla world loading screen ({@link LevelLoadingScreen} in 26.2) is not open,
 * ③ the chunk under the player is in the level. A scene that arrives before the one before it was answered
 * replaces it, so only the newest is answered.
 * <p>
 * RUN-077 group V measured a move between instances of one dimension type on the real client: the loading
 * screen stays open three ticks while the chunk underfoot is there from the new level's first tick, so ② is
 * the condition that holds last.
 */
public final class SceneReadyProbe {

    /**
     * The client at the end of one tick, as far as the three conditions go.
     *
     * @param inWorld        whether the client has a level and a player
     * @param screen         the class of the open screen, null for none
     * @param chunkUnderfoot whether the level holds the chunk under the player
     */
    public record View(boolean inWorld, @Nullable Class<?> screen, boolean chunkUnderfoot) {
    }

    /**
     * The client at the moment the probe sent a {@code C2S_SCENE_READY}.
     *
     * @param sceneSeq       the scene answered
     * @param screen         the class name of the screen open then, null for none
     * @param chunkUnderfoot whether the chunk under the player was in the level then
     * @param nanos          the client clock then
     */
    public record Sent(int sceneSeq, @Nullable String screen, boolean chunkUnderfoot, long nanos) {
    }

    private final Consumer<PlayMessage> sender;
    private int answered;
    private @Nullable Sent last;

    /**
     * @param sender where the {@code C2S_SCENE_READY} goes
     */
    public SceneReadyProbe(Consumer<PlayMessage> sender) {
        this.sender = sender;
    }

    /**
     * Judges one client tick.
     *
     * @param scene the scene the connection is in, null for none
     * @param view  the client at the end of the tick
     * @param nanos the client clock
     * @return whether it sent a {@code C2S_SCENE_READY}
     */
    public boolean tick(PlayClient.@Nullable Scene scene, View view, long nanos) {
        return false;
    }

    /**
     * Conditions ② and ③.
     *
     * @param view the client
     * @return whether the client stands in a loaded level
     */
    public static boolean ready(View view) {
        return false;
    }

    /**
     * @return the client at the last {@code C2S_SCENE_READY} this connection sent, null before the first
     */
    public @Nullable Sent last() {
        return last;
    }

    /** Forgets the connection: the next connection's scenes count from 1 again. */
    public void reset() {
        answered = 0;
        last = null;
    }
}
