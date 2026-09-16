package io.github.bearisbug.synaxis.game.rts;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.LightingChunk;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.plugin.Admission;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.PluginContext;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;

/**
 * The RTS plugin, the one compile time plugin of M2.
 * <p>
 * It owns the match: it builds the instance before the server binds its port and tells the host, by
 * way of the admission provider, where a connecting player belongs. The generated map, the units
 * and the pathfinding arrive with REQ-M2-010, REQ-M2-016 and REQ-M2-017; until then the match is a
 * flat world, which is what keeps the protocol smoke tests of the engine runnable.
 */
public final class RtsPlugin implements SynaxisPlugin {

    private static final PluginDescriptor DESCRIPTOR =
            new PluginDescriptor("rts", "0.1.0", PluginApi.API_VERSION);

    private static final int GROUND_HEIGHT = 40;
    private static final Pos SPAWN = new Pos(0, 42, 0);
    private static final int PLAYER_TEAM = 1;

    @Override
    public PluginDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void onEnable(PluginContext context) {
        context.logger().info("Enabling: plugin_id={} version={} data_directory={}",
                DESCRIPTOR.id(), DESCRIPTOR.version(), context.dataDirectory());

        final InstanceContainer match = context.process().instance().createInstanceContainer();
        match.setGenerator(unit -> unit.modifier().fillHeight(0, GROUND_HEIGHT, Block.STONE));
        match.setChunkSupplier(LightingChunk::new);

        context.events().addListener(AsyncPlayerConfigurationEvent.class,
                event -> event.getPlayer().setRespawnPoint(SPAWN));
        context.admission().register(_ -> new Admission.Accept(match, PLAYER_TEAM));
    }

    @Override
    public void onDisable() {
        // Nothing of its own to release: the host takes back the event node, the tasks, the
        // channels and the admission provider, and the engine drops the instance on shutdown.
    }
}
