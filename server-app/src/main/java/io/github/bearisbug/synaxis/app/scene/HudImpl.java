package io.github.bearisbug.synaxis.app.scene;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.plugin.play.Hud;
import io.github.bearisbug.synaxis.plugin.play.Widget;

/**
 * The widgets one plugin shows one player: the plugin is the owner of every widget set through here.
 */
final class HudImpl implements Hud {

    private final String pluginId;
    private final Player player;
    private final SceneService scenes;

    HudImpl(String pluginId, Player player, SceneService scenes) {
        this.pluginId = pluginId;
        this.player = player;
        this.scenes = scenes;
    }

    @Override
    public void set(Widget widget) {
        scenes.set(pluginId, player, widget);
    }

    @Override
    public void remove(String widgetId) {
        scenes.remove(pluginId, player, widgetId);
    }

    @Override
    public void clear() {
        scenes.clear(pluginId, player);
    }
}
