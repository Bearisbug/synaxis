package io.github.bearisbug.synaxis.game.rush;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.game.playkit.LocalQueue;
import io.github.bearisbug.synaxis.plugin.PluginAnalytics;
import io.github.bearisbug.synaxis.plugin.instance.ManagedInstance;
import io.github.bearisbug.synaxis.plugin.instance.PluginInstances;
import io.github.bearisbug.synaxis.plugin.play.ActionResult;
import org.slf4j.Logger;

import java.util.function.LongSupplier;

/**
 * The lobby of rush ({@code docs/DESIGN-PLAY.md} §6.7.3); the skeleton of RUN-077 group R.
 */
final class Lobby {

    Lobby(PluginInstances instances, ManagedInstance lobby, LocalQueue<Player> queue, RushConfig config, RushHud hud,
          PluginAnalytics analytics, LongSupplier clock, Logger logger) {
    }

    void arrived(Player player) {
    }

    void left(Player player) {
    }

    ActionResult toggleQueue(Player player) {
        return new ActionResult.Ok();
    }

    ActionResult backToLobby(Player player) {
        return new ActionResult.Ok();
    }

    void tick() {
    }
}
