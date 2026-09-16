package io.github.bearisbug.synaxis.game.rts;

import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.plugin.Admission;
import io.github.bearisbug.synaxis.plugin.DisconnectKeys;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.PluginContext;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.world.DimensionType;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.Nullable;

/**
 * The RTS plugin, the one compile time plugin of M2.
 * <p>
 * It owns the match: it generates the map and builds the instance before the server binds its port
 * and tells the host, by way of the admission provider, where a connecting player belongs. The
 * units and the pathfinding arrive with REQ-M2-016 and REQ-M2-017; the navigation mesh they will
 * read is already generated with the map (REQ-M2-010).
 */
public final class RtsPlugin implements SynaxisPlugin {

    private static final PluginDescriptor DESCRIPTOR =
            new PluginDescriptor("rts", "0.1.0", PluginApi.API_VERSION);

    /** The map preset to generate, {@link MapPreset#DEFAULT} when the operator names none. */
    private static final String MAP_PROPERTY = "synaxis.app.map";

    private static final int PLAYER_TEAM = 1;

    private @Nullable Match match;

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

        final Match started = new Match(context.process(), dimensionType, context.analytics(), context.logger());
        started.load(System.getProperty(MAP_PROPERTY, MapPreset.DEFAULT.id()));
        this.match = started;

        // Nothing else is wired when the map did not come up: the host refuses to bind without a
        // running match, and the admission provider would have no instance to hand out anyway.
        final InstanceContainer instance = started.instance();
        if (instance == null) return;

        context.events().addListener(AsyncPlayerConfigurationEvent.class,
                event -> event.getPlayer().setRespawnPoint(started.spawn()));
        context.admission().register(_ -> started.state() == Match.State.RUNNING
                ? new Admission.Accept(instance, PLAYER_TEAM)
                : new Admission.Reject(DisconnectKeys.MATCH_UNAVAILABLE));
    }

    @Override
    public void onDisable() {
        final Match running = this.match;
        if (running != null) running.end();
    }
}
