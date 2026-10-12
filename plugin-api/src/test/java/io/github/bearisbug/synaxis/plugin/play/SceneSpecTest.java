package io.github.bearisbug.synaxis.plugin.play;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The limits of a scene that need no encoding ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-005},
 * {@code S2C_SCENE} of {@code API-PLAY-002}, §9): each action key 0 to 5 and used once, which is
 * also what keeps a scene to six of them, and a cooldown of 0 to 600 s. A scene keeps its own copy
 * of the slots.
 */
class SceneSpecTest {

    private static final Component LABEL = Component.text("label");

    private static ActionSlot slot(int slot) {
        return new ActionSlot(slot, "action_" + slot, LABEL, Duration.ofSeconds(2));
    }

    private static SceneSpec scene(List<ActionSlot> actions) {
        return new SceneSpec("lobby", Component.text("Lobby"), new CameraSpec.Vanilla(), CursorMode.LOCKED, actions);
    }

    @Test
    void aSceneTakesSixActionsInDistinctSlotsAndKeepsItsOwnCopy() {
        final List<ActionSlot> actions = new ArrayList<>(IntStream.rangeClosed(0, 5).mapToObj(SceneSpecTest::slot).toList());
        final SceneSpec scene = scene(actions);
        actions.clear();

        assertEquals(6, scene.actions().size(), "the caller's list changed after the scene was built");
        assertThrows(UnsupportedOperationException.class, () -> scene.actions().add(slot(0)));
    }

    @Test
    void aSlotUsedTwiceIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> scene(List.of(slot(1), slot(0), slot(1))));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 6})
    void anActionKeyOutsideZeroToFiveIsRefused(int slot) {
        assertThrows(IllegalArgumentException.class, () -> slot(slot));
    }

    @Test
    void aCooldownOutsideZeroTo600SecondsIsRefused() {
        assertEquals(Duration.ZERO, new ActionSlot(0, "queue", LABEL, Duration.ZERO).cooldown());
        assertEquals(Duration.ofMinutes(10), new ActionSlot(0, "queue", LABEL, Duration.ofMinutes(10)).cooldown());

        assertThrows(IllegalArgumentException.class, () -> new ActionSlot(0, "queue", LABEL, Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class, () -> new ActionSlot(0, "queue", LABEL, Duration.ofMillis(600_001)));
    }
}
