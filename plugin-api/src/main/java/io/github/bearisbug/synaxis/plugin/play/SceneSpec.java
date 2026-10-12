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
 * @param actions the action slots, at most 6, no slot twice; copied
 */
public record SceneSpec(String id, Component title, CameraSpec camera, CursorMode cursor, List<ActionSlot> actions) {
}
