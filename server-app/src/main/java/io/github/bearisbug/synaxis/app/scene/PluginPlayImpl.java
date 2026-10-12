package io.github.bearisbug.synaxis.app.scene;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.plugin.play.ActionHandler;
import io.github.bearisbug.synaxis.plugin.play.Hud;
import io.github.bearisbug.synaxis.plugin.play.PluginPlay;
import io.github.bearisbug.synaxis.plugin.play.SceneSpec;

import java.util.Optional;

/**
 * The scene layer as one plugin sees it: every call carries that plugin's id into {@link SceneService}.
 */
final class PluginPlayImpl implements PluginPlay {

    private final String pluginId;
    private final SceneService scenes;

    PluginPlayImpl(String pluginId, SceneService scenes) {
        this.pluginId = pluginId;
        this.scenes = scenes;
    }

    @Override
    public void enter(Player player, SceneSpec scene) {
        scenes.enter(pluginId, player, scene);
    }

    @Override
    public void leave(Player player) {
        scenes.leave(pluginId, player);
    }

    @Override
    public Optional<String> currentScene(Player player) {
        return scenes.currentScene(player);
    }

    @Override
    public Hud hud(Player player) {
        return new HudImpl(pluginId, player, scenes);
    }

    @Override
    public void onAction(String actionId, ActionHandler handler) {
        scenes.onAction(pluginId, actionId, handler);
    }
}
