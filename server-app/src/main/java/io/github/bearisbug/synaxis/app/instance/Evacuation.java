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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Sends the players of a closing managed instance away and unregisters it once they are gone
 * ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-006}, internal task {@code play.instance.evacuate}).
 * <p>
 * Each player online inside when the instance closed gets the exit the plugin chose, and so does a
 * player whose move was admitted before the close and lands after it. The instance is unregistered as
 * soon as nobody is left and no such move is on its way, on the startup thread or in a serial part of
 * the tick scheduler thread; whoever is still inside when the evacuation time runs out is kicked with
 * {@code synaxis:match-unavailable} first.
 */
final class Evacuation {

    private static final Logger LOGGER = LoggerFactory.getLogger(Evacuation.class);
    private static final Component MATCH_UNAVAILABLE = Component.translatable(DisconnectKeys.MATCH_UNAVAILABLE);

    private final ManagedInstanceImpl managed;
    private final InstanceRegistry registry;
    private final Function<Player, Exit> exit;
    private final boolean settleTimeout;
    /** The players handed the exit: those online inside when the instance closed, then the late arrivals. */
    private final Set<Player> inside = ConcurrentHashMap.newKeySet();
    private final long startedAt;
    private final AtomicBoolean finished = new AtomicBoolean();

    /**
     * Takes the players online inside the instance, which is turning {@code CLOSED} under its monitor.
     *
     * @param exit          where each player goes
     * @param settleTimeout whether the instance closes because its plugin did not settle it in time
     */
    Evacuation(ManagedInstanceImpl managed, InstanceRegistry registry, Function<Player, Exit> exit, boolean settleTimeout) {
        this.managed = managed;
        this.registry = registry;
        this.exit = exit;
        this.settleTimeout = settleTimeout;
        this.inside.addAll(managed.online());
        this.startedAt = registry.now();
    }

    /**
     * Hands every player their exit, then unregisters the instance at once if nobody is left and the
     * calling thread may, or watches it once a tick until then. Does nothing once a forced close
     * finished the evacuation, which a listener of the closing event may have done.
     */
    void start() {
        if (finished.get()) return;
        for (Player player : inside) send(player);
        if (registry.inSerialPart() && nobodyLeft()) finish();
        else registry.process().scheduler().scheduleTask(this::sweep, TaskSchedule.nextTick());
    }

    /**
     * A player whose move was admitted before the instance closed has landed in it.
     *
     * @param player the player, on their tick thread
     */
    void arrived(Player player) {
        if (finished.get()) {
            player.kick(MATCH_UNAVAILABLE);
            return;
        }
        inside.add(player);
        send(player);
    }

    /** The exit of one player; one the plugin's function cannot give, by throwing or by giving null, is a kick. */
    private void send(Player player) {
        try {
            switch (exit.apply(player)) {
                case Exit.ToInstance(ManagedInstance to, Pos at) -> {
                    var _ = to.move(player, at);
                }
                case Exit.Kick(String reasonKey) -> player.kick(Component.translatable(reasonKey));
            }
        } catch (RuntimeException e) {
            LOGGER.error("No exit for a player of a closing managed instance, kicking them: plugin_id={} instance={} "
                    + "connection_id={}", managed.pluginId(), managed.id(), player.getPlayerConnection().connectionId(), e);
            player.kick(MATCH_UNAVAILABLE);
        }
    }

    private boolean nobodyLeft() {
        return managed.online().isEmpty() && !managed.movesPending();
    }

    /** Once a tick, in the serial part of the tick scheduler thread. */
    private TaskSchedule sweep() {
        if (finished.get()) return TaskSchedule.stop();
        final boolean timeUp = registry.now() - startedAt >= registry.durations().evacuation().toNanos();
        if (managed.movesPending()) return TaskSchedule.nextTick();
        if (!managed.online().isEmpty() && !timeUp) return TaskSchedule.nextTick();
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
        final List<Player> left = managed.online();
        for (Player player : left) player.kick(MATCH_UNAVAILABLE);
        registry.process().instance().unregisterInstance(managed.instance());
        final int evacuated = (int) inside.stream().filter(player -> !left.contains(player)).count();
        managed.closed(inside.size(), evacuated, left.size(), settleTimeout);
    }
}
