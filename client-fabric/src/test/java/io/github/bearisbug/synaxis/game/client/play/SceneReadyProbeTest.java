package io.github.bearisbug.synaxis.game.client.play;

import io.github.bearisbug.synaxis.game.protocol.play.C2SSceneReady;
import io.github.bearisbug.synaxis.game.protocol.play.PlayMessage;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three conditions of {@code docs/DESIGN-PLAY.md} §6.4.3 under which the client answers a scene with
 * {@code C2S_SCENE_READY}: the scene applied, a level and a player with no world loading screen open, the chunk
 * underfoot loaded; one answer per scene, only for the newest, and what the client looked like when it went.
 */
class SceneReadyProbeTest {

    private static final SceneReadyProbe.View READY = new SceneReadyProbe.View(true, null, true);

    private final List<PlayMessage> sent = new ArrayList<>();
    private final SceneReadyProbe probe = new SceneReadyProbe(sent::add);

    @Test
    void aSceneIsAnsweredOnceAtTheFirstTickAllThreeHold() {
        assertFalse(probe.tick(scene(1), new SceneReadyProbe.View(false, null, false), 10));
        assertFalse(probe.tick(scene(1), new SceneReadyProbe.View(true, LevelLoadingScreen.class, true), 20));
        assertFalse(probe.tick(scene(1), new SceneReadyProbe.View(true, null, false), 30));
        assertTrue(probe.tick(scene(1), READY, 40));
        assertFalse(probe.tick(scene(1), READY, 50));

        assertEquals(List.of(new C2SSceneReady(1)), sent);
        assertEquals(new SceneReadyProbe.Sent(1, null, true, 40), probe.last());
    }

    @Test
    void theWorldLoadingScreenHoldsItBackWhileAnyOtherScreenDoesNot() {
        assertFalse(SceneReadyProbe.ready(new SceneReadyProbe.View(true, LevelLoadingScreen.class, true)));
        assertTrue(SceneReadyProbe.ready(new SceneReadyProbe.View(true, ChatScreen.class, true)));
        assertTrue(SceneReadyProbe.ready(new SceneReadyProbe.View(true, PanelScreen.class, true)));

        assertTrue(probe.tick(scene(2), new SceneReadyProbe.View(true, ChatScreen.class, true), 7));
        assertEquals(new SceneReadyProbe.Sent(2, ChatScreen.class.getName(), true, 7), probe.last());
    }

    @Test
    void noSceneIsNeverAnswered() {
        assertFalse(probe.tick(null, READY, 1));
        assertEquals(List.of(), sent);
        assertNull(probe.last());
    }

    @Test
    void aSceneThatReplacesOneNotYetAnsweredIsTheOnlyOneAnswered() {
        probe.tick(scene(1), new SceneReadyProbe.View(true, LevelLoadingScreen.class, true), 1);
        probe.tick(scene(2), new SceneReadyProbe.View(true, LevelLoadingScreen.class, true), 2);
        probe.tick(scene(2), READY, 3);
        probe.tick(scene(3), READY, 4);

        assertEquals(List.of(new C2SSceneReady(2), new C2SSceneReady(3)), sent);
    }

    @Test
    void resetLetsTheNextConnectionsFirstSceneBeAnswered() {
        probe.tick(scene(1), READY, 1);
        probe.reset();
        assertNull(probe.last());

        assertTrue(probe.tick(scene(1), READY, 2));
        assertEquals(List.of(new C2SSceneReady(1), new C2SSceneReady(1)), sent);
    }

    private static PlayClient.Scene scene(int seq) {
        return new PlayClient.Scene(seq, "rush:lobby", Component.empty(), List.of());
    }
}
