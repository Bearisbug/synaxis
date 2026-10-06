package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.WorldBorder;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.plugin.Admission;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.world.DimensionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * The core mode of the startup contract (API-M2-010 step 5): the server core serving itself, with
 * no game on top of it.
 * <p>
 * Gameplay comes from a plugin, and the host needs both halves of it — an admission provider to say
 * where a connection goes and a running match to put it in. A process with no plugin at all used to
 * refuse to open the port over the missing halves, which made the core impossible to run, load test
 * or smoke test on its own. It now serves a world of its own instead: one flat instance, an
 * admission provider that lets everybody into it, and the {@value #ECHO_CHANNEL} diagnostic channel
 * that sends every payload it receives straight back.
 * <p>
 * A plugin that did take part still has to deliver: half provided gameplay, or none at all out of a
 * plugin that came up wrong, is a refusal exactly as it was before.
 */
final class CoreMode {

    private static final Logger LOGGER = LoggerFactory.getLogger(CoreMode.class);

    /**
     * Which of the two modes the operator wants, {@code auto} when unset.
     */
    static final String PROPERTY = "synaxis.app.core-mode";

    /**
     * The one channel of the host's own namespace: it echoes what it receives, which is what makes
     * a payload round trip provable without a plugin ({@code docs/TEST.md} §3, probe {@code echo}).
     */
    static final String ECHO_CHANNEL = "synaxis:main";

    /**
     * The floor of the core world: every column is stone below it and air above it.
     */
    private static final int FLOOR_HEIGHT = 40;

    /**
     * Where a connection is put, standing on the floor. The core world has no other landmark, so
     * everybody spawns in the same column.
     */
    static final Pos SPAWN = new Pos(0.5, FLOOR_HEIGHT, 0.5);

    /**
     * The width of the core world in blocks, its world border centred on the origin. The world never
     * unloads a chunk, so the border is what bounds the chunks a client walking away makes it hold
     * ({@code docs/DESIGN-PROD.md} {@code API-PROD-030}): 256 chunks across, and 278 with the chunk
     * rings of view distance 10 reaching 11 chunks past it, at 9,013 B a chunk with its cached packet
     * is 16% of the 4 GiB default heap. The circles of the load test bots stay within 1,536 blocks of
     * the spawn.
     */
    private static final int BORDER_DIAMETER = 4096;

    /**
     * §9: team 0 is the neutral team, and the core mode has no sides.
     */
    private static final int TEAM = 0;

    /**
     * What the operator asked for.
     */
    enum Setting {
        /** Serve the core world when, and only when, no plugin provides gameplay. */
        AUTO,
        /** Serve the core world, plugins not discovered at all. */
        ON,
        /** Never serve the core world: a launch without gameplay is refused, as it was before. */
        OFF
    }

    /**
     * What the process does once its plugins had their turn.
     */
    enum Mode {
        /** The host serves its own world. */
        CORE,
        /** A plugin provides both the admission provider and the one running match. */
        GAMEPLAY,
        /** Neither is true: report it and exit without binding. */
        REFUSED
    }

    private CoreMode() {
    }

    /**
     * @return the requested setting, {@link Setting#AUTO} when the property is not set
     * @throws IllegalArgumentException if the property is set to something that is not a setting
     */
    static Setting setting() {
        final String property = System.getProperty(PROPERTY);
        if (property == null) return Setting.AUTO;
        final String value = property.strip().toUpperCase(Locale.ROOT);
        for (Setting setting : Setting.values())
            if (setting.name().equals(value)) return setting;
        throw new IllegalArgumentException(PROPERTY + "=" + property + " is not one of auto, on, off");
    }

    /**
     * Step 5 of the startup sequence.
     * <p>
     * The core mode needs an empty stage, not just a missing capability: a plugin that took part in
     * the startup and left no gameplay behind (its {@code onEnable} threw, its map was refused) is a
     * plugin that came up wrong, and covering that up with a world of our own would turn a launch
     * that has to fail loudly into a server nobody can play on.
     *
     * @param setting              what the operator asked for
     * @param attemptedPlugins     how many plugins the host took on
     * @param hasAdmissionProvider whether a plugin registered one
     * @param runningMatches       the registered instances, the host side view of "a match is
     *                             running" (§11.3)
     * @return the mode to serve in
     */
    static Mode decide(Setting setting, int attemptedPlugins, boolean hasAdmissionProvider, int runningMatches) {
        if (setting == Setting.ON) return Mode.CORE;
        if (hasAdmissionProvider && runningMatches == 1) return Mode.GAMEPLAY;
        if (setting == Setting.AUTO && attemptedPlugins == 0 && !hasAdmissionProvider && runningMatches == 0)
            return Mode.CORE;
        return Mode.REFUSED;
    }

    /**
     * Builds the core world and takes the two capabilities the host would otherwise get from a
     * plugin. Called from the startup thread, before the port is bound.
     *
     * @param process       the engine process
     * @param host          the plugin host, whose admission slot and channel router the core mode
     *                      borrows
     * @param dimensionType the one shallow dimension type of step 3
     * @param setting       what the operator asked for, so that the log says why this mode is the
     *                      one being served
     * @return the core world
     */
    static InstanceContainer start(ServerProcess process, PluginHost host,
                                   RegistryKey<DimensionType> dimensionType, Setting setting) {
        final InstanceContainer world = process.instance().createInstanceContainer(dimensionType);
        world.setGenerator(unit -> unit.modifier().fillHeight(0, FLOOR_HEIGHT, Block.STONE));
        world.setWorldBorder(WorldBorder.defaultBorder().withDiameter(BORDER_DIAMETER));
        // Automatic chunk loading stays on, unlike a match: within the border the core world is a flat
        // plane, so a column is generated whenever somebody gets close enough to it.
        process.eventHandler().addListener(AsyncPlayerConfigurationEvent.class,
                event -> event.getPlayer().setRespawnPoint(SPAWN));
        host.registerHostAdmission(_ -> new Admission.Accept(world, TEAM));
        host.registerHostChannel(ECHO_CHANNEL, (player, payload) -> player.sendPluginMessage(ECHO_CHANNEL, payload));
        LOGGER.info("Core mode ({}={}): no plugin is serving gameplay, so the host serves its own flat world. "
                + "spawn={} channel={} (echoes every payload back)",
                PROPERTY, setting.name().toLowerCase(Locale.ROOT), SPAWN, ECHO_CHANNEL);
        return world;
    }
}
