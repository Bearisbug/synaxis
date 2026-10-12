package io.github.bearisbug.synaxis.plugin.play;

import net.kyori.adventure.text.Component;

import java.util.List;

/**
 * A scene a player is put into with {@link PluginPlay#enter} ({@code docs/DESIGN-PLAY.md}
 * {@code API-PLAY-005}, {@code S2C_SCENE} of {@code API-PLAY-002}).
 *
 * @param id      the scene id, with or without this plugin's prefix
 * @param title   the title, a translate key with a fallback where the text is new
 * @param camera  the camera
 * @param cursor  the cursor mode
 * @param actions the action slots, no slot twice, which also keeps them to 6; copied
 */
public record SceneSpec(String id, Component title, CameraSpec camera, CursorMode cursor, List<ActionSlot> actions) {

    /**
     * Copies the actions and checks their slots.
     *
     * @throws IllegalArgumentException if two actions share a slot
     */
    public SceneSpec {
        actions = List.copyOf(actions);
        final boolean[] taken = new boolean[6];
        for (final ActionSlot action : actions) {
            if (taken[action.slot()])
                throw new IllegalArgumentException("action key " + action.slot() + " is used twice in scene " + id);
            taken[action.slot()] = true;
        }
    }
}
