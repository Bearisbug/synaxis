package io.github.bearisbug.synaxis.game.rts;

import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.event.player.PlayerDisconnectEvent;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.plugin.Admission;
import io.github.bearisbug.synaxis.plugin.DisconnectKeys;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.PluginContext;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.timer.TaskSchedule;
import io.github.bearisbug.synaxis.world.DimensionType;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * The RTS plugin, the one compile time plugin of M2.
 * <p>
 * It owns the match: it generates the map, builds the instance and deploys the roster before the
 * server binds its port, then tells the host, by way of the admission provider, where a connecting
 * player belongs. It also owns the one game channel of the slice, where the server judges what a
 * client asks for ({@link GameChannel}), and the one scheduled task that drives the units — the
 * pathfinding queue, the advance along the waypoints and the snapshot ({@link Units}).
 */
public final class RtsPlugin implements SynaxisPlugin {

    private static final PluginDescriptor DESCRIPTOR =
            new PluginDescriptor("rts", "0.1.0", PluginApi.API_VERSION);

    /** The map preset to generate, {@link MapPreset#DEFAULT} when the operator names none. */
    private static final String MAP_PROPERTY = "synaxis.app.map";

    private @Nullable Match match;
    private @Nullable Units units;

    @Override
    public PluginDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void onEnable(PluginContext context) {
        context.logger().info("Enabling: plugin_id={} version={} data_directory={}",
                DESCRIPTOR.id(), DESCRIPTOR.version(), context.dataDirectory());

        final RegistryKey<DimensionType> dimensionType = context.process().dimensionType()
                .getKey(Key.key(PluginApi.MATCH_DIMENSION_TYPE));
        if (dimensionType == null)
            throw new IllegalStateException("the host did not register " + PluginApi.MATCH_DIMENSION_TYPE
                    + ", there is no dimension to build a match in");

        // Read before the map is built: a refused number is a refused launch, and it should not
        // cost a thousand chunks to find out
        final int rosterSize = Roster.units();
        final Match started = new Match(context.process(), dimensionType, context.analytics(), context.logger());
        started.load(System.getProperty(MAP_PROPERTY, MapPreset.DEFAULT.id()));
        this.match = started;

        // Nothing else is wired when the map did not come up: the host refuses to bind without a
        // running match, and the admission provider would have no instance to hand out anyway.
        final InstanceContainer instance = started.instance();
        if (instance == null) return;

        context.events().addListener(AsyncPlayerConfigurationEvent.class,
                event -> event.getPlayer().setRespawnPoint(started.spawn()));

        final NavGrid navGrid = Objects.requireNonNull(started.navGrid(), "the match has no navigation mesh");
        final MapPreset preset = Objects.requireNonNull(started.preset(), "the match has no map");
        final GameSessions sessions = new GameSessions(context.process().connection());
        final Units deployed = new Units(started, instance, navGrid, preset, sessions,
                context.channels(), context.analytics(), System::nanoTime);
        Roster.deploy(deployed, navGrid, preset, started.spawn(), rosterSize);
        this.units = deployed;

        final GameChannel game = new GameChannel(started, sessions, deployed, context.channels(),
                context.analytics(), System::nanoTime);
        context.channels().register(GameChannel.NAME, game::receive);
        context.events().addListener(PlayerDisconnectEvent.class, event -> {
            final UUID connectionId = event.getPlayer().getPlayerConnection().connectionId();
            sessions.closed(connectionId);
            deployed.closed(connectionId);
        });
        // One task, every tick: the searches the budget allows, and on every other tick the advance
        // and the snapshot. It runs at the top of a tick, while the tick threads wait (API-M2-010 step 6
        // starts them), but not apart from a sync on another thread: it moves the units directly only
        // because Entity#moveTo asserts no ownership, the serial part is no licence to change entities
        context.scheduler().scheduleTask(deployed::tick, TaskSchedule.nextTick(), TaskSchedule.tick(1));
        context.admission().register(request -> {
            if (started.state() != Match.State.RUNNING) return new Admission.Reject(DisconnectKeys.MATCH_UNAVAILABLE);
            // The team the connection is admitted with is the sender half of owner(unit, sender),
            // and this is the only place it is decided (§9)
            sessions.admitted(request.connectionId(), Roster.PLAYER_TEAM);
            return new Admission.Accept(instance, Roster.PLAYER_TEAM);
        });
    }

    @Override
    public void onDisable() {
        final Units deployed = this.units;
        if (deployed != null) deployed.end();
        final Match running = this.match;
        if (running != null) running.end();
    }
}
