package io.github.bearisbug.synaxis.app.instance;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.plugin.DisconnectKeys;
import io.github.bearisbug.synaxis.plugin.instance.Exit;
import io.github.bearisbug.synaxis.plugin.instance.ManagedInstance;
import io.github.bearisbug.synaxis.timer.TaskSchedule;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Sends the players of a closing managed instance away and unregisters it once they are gone
 * ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-006}, internal task {@code play.instance.evacuate}).
 * <p>
 * Each player online inside when the instance closed gets the exit the plugin chose. The instance is
 * unregistered as soon as nobody is left, on the startup thread or in a serial part of the tick
 * scheduler thread; whoever is still inside when the evacuation time runs out is kicked with
 * {@code synaxis:match-unavailable} first.
 */
final class Evacuation {

    private static final Logger LOGGER = LoggerFactory.getLogger(Evacuation.class);
    private static final Component MATCH_UNAVAILABLE = Component.translatable(DisconnectKeys.MATCH_UNAVAILABLE);

    private final ManagedInstanceImpl managed;
    private final InstanceRegistry registry;
    private final boolean settleTimeout;
    private final List<Player> inside;
    private final long startedAt;
    private final AtomicBoolean finished = new AtomicBoolean();

    /**
     * Takes the players online inside the instance, which has just turned {@code CLOSED}.
     *
     * @param settleTimeout whether the instance closes because its plugin did not settle it in time
     */
    Evacuation(ManagedInstanceImpl managed, InstanceRegistry registry, boolean settleTimeout) {
        this.managed = managed;
        this.registry = registry;
        this.settleTimeout = settleTimeout;
        this.inside = online();
        this.startedAt = registry.now();
    }

    /**
     * Hands every player their exit, then unregisters the instance at once if nobody is left and the
     * calling thread may, or watches it once a tick until then.
     *
     * @param exit where each player goes
     */
    void start(Function<Player, Exit> exit) {
        for (Player player : inside) send(player, exit);
        if (registry.inSerialPart() && online().isEmpty()) finish();
        else registry.process().scheduler().scheduleTask(this::sweep, TaskSchedule.nextTick());
    }

    private void send(Player player, Function<Player, Exit> exit) {
        Exit target;
        try {
            target = exit.apply(player);
        } catch (RuntimeException e) {
            LOGGER.error("The exit of a closing managed instance threw, kicking the player: plugin_id={} instance={} "
                    + "connection_id={}", managed.pluginId(), managed.id(), player.getPlayerConnection().connectionId(), e);
            target = new Exit.Kick(DisconnectKeys.MATCH_UNAVAILABLE);
        }
        switch (target) {
            case Exit.ToInstance(ManagedInstance to, Pos at) -> {
                var _ = to.move(player, at);
            }
            case Exit.Kick(String reasonKey) -> player.kick(Component.translatable(reasonKey));
        }
    }

    /** Once a tick, in the serial part of the tick scheduler thread. */
    private TaskSchedule sweep() {
        if (finished.get()) return TaskSchedule.stop();
        if (!online().isEmpty() && registry.now() - startedAt < registry.durations().evacuation().toNanos())
            return TaskSchedule.nextTick();
        finish();
        return TaskSchedule.stop();
    }

    /**
     * Kicks whoever is still online inside, unregisters the instance and writes its closing line,
     * unless that was done already. A kick takes the connection offline at once, which is what lets
     * the engine unregister right after.
     */
    void finish() {
        if (!finished.compareAndSet(false, true)) return;
        final List<Player> left = online();
        for (Player player : left) player.kick(MATCH_UNAVAILABLE);
        registry.process().instance().unregisterInstance(managed.instance());
        final int evacuated = (int) inside.stream().filter(player -> !left.contains(player)).count();
        managed.closed(inside.size(), evacuated, left.size(), settleTimeout);
    }

    private List<Player> online() {
        return managed.instance().getPlayers().stream().filter(Player::isOnline).toList();
    }
}
