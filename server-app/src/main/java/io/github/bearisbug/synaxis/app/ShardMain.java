package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.app.shard.ShardSettings;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The launcher of an MMO shard: the server application of {@link Main} with the shard defaults on
 * top ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-020}, {@code API-PROD-016};
 * {@code docs/DESIGN-M3.md} {@code API-M3-006}). The chunks are spread over the tick threads by
 * cells of the grid, and the shard takes all cores but two for them, leaving those to the tick
 * scheduler, the network carriers and the GC. A player sees 12 chunks, is sent at most 4 of them a
 * tick and is shown at most 100 entities. A shard storing in PostgreSQL takes transfers; one storing
 * in memory does not, as {@link Main} refuses to take them there. The RTS process is launched
 * through {@link Main} and keeps the engine defaults, the counter and one tick thread. A shard given
 * {@code synaxis.map.root} and {@code synaxis.map.build} mounts that map release and loads no plugin,
 * which only this launcher does. The connection gate of a shard holds 3 sockets a seat of its
 * capacity and lets 5 players into the game a tick ({@code API-PROD-003}).
 * <p>
 * A value the operator passed on the command line wins, {@code -Dsynaxis.thread-provider=counter}
 * takes a shard back to the engine provider.
 */
public final class ShardMain {

    static final String THREAD_PROVIDER = "synaxis.thread-provider";
    static final String DISPATCHER_THREADS = "synaxis.dispatcher-threads";
    static final String CHUNK_VIEW_DISTANCE = "synaxis.chunk-view-distance";
    static final String MAX_CHUNKS_PER_TICK = "synaxis.chunk-queue.max-per-tick";
    static final String MAX_VISIBLE_ENTITIES = "synaxis.max-visible-entities-per-player";
    static final String ACCEPT_TRANSFERS = "synaxis.accept-transfers";
    static final String MAX_PLAYERS = "synaxis.max-players";
    static final String MAX_CONNECTIONS = "synaxis.max-connections";
    static final String MAX_JOINS_PER_TICK = "synaxis.max-joins-per-tick";
    static final String MAP_ROOT = ShardSettings.MAP_ROOT;
    static final String MAP_BUILD = ShardSettings.MAP_BUILD;

    private ShardMain() {
    }

    public static void main(String[] args) {
        // synaxis.properties first, which Main applies as it is initialised: the defaults below only
        // fill what neither the command line nor the file set. A refused file is Main's to report.
        if (Main.configFile().refusal() == null) {
            // Before Main reads anything: the engine takes its settings while the process is created
            threadDefaults(System::getProperty, Runtime.getRuntime().availableProcessors()).forEach(System::setProperty);
            viewDefaults(System::getProperty).forEach(System::setProperty);
            transferDefaults(System::getProperty).forEach(System::setProperty);
            mapDefaults(System::getProperty).forEach(System::setProperty);
        }
        Main.main(args, true);
    }

    /**
     * Returns the thread properties to set, the ones the command line left out.
     *
     * @param properties the system properties already set
     * @param cores      the processors of the machine
     * @return the properties and their values
     */
    static Map<String, String> threadDefaults(UnaryOperator<String> properties, int cores) {
        final Map<String, String> defaults = new LinkedHashMap<>();
        if (properties.apply(THREAD_PROVIDER) == null) defaults.put(THREAD_PROVIDER, "grid");
        if (properties.apply(DISPATCHER_THREADS) == null)
            defaults.put(DISPATCHER_THREADS, Integer.toString(Math.max(1, cores - 2)));
        return defaults;
    }

    /**
     * Returns the view distance and the caps on chunks sent and entities shown to set, the ones the
     * command line left out.
     *
     * @param properties the system properties already set
     * @return the properties and their values
     */
    static Map<String, String> viewDefaults(UnaryOperator<String> properties) {
        final Map<String, String> defaults = new LinkedHashMap<>();
        if (properties.apply(CHUNK_VIEW_DISTANCE) == null) defaults.put(CHUNK_VIEW_DISTANCE, "12");
        if (properties.apply(MAX_CHUNKS_PER_TICK) == null) defaults.put(MAX_CHUNKS_PER_TICK, "4");
        if (properties.apply(MAX_VISIBLE_ENTITIES) == null) defaults.put(MAX_VISIBLE_ENTITIES, "100");
        return defaults;
    }

    /**
     * Returns {@code synaxis.accept-transfers=true} when the shard stores in PostgreSQL and the
     * command line left the property out. A shard storing in memory is left on the engine value,
     * {@link Main} refuses to start one that takes transfers.
     *
     * @param properties the system properties already set
     * @return the property and its value, or nothing
     */
    static Map<String, String> transferDefaults(UnaryOperator<String> properties) {
        final String store = properties.apply(Main.STORE_PROPERTY);
        if (properties.apply(ACCEPT_TRANSFERS) != null || store == null || !Main.STORE_POSTGRES.equals(store.strip()))
            return Map.of();
        return Map.of(ACCEPT_TRANSFERS, "true");
    }

    /**
     * Returns the socket cap and the cap on joins a tick of the connection gate to set, the ones the
     * command line left out ({@code docs/DESIGN-PROD.md} {@code API-PROD-003}): 3 sockets a seat of
     * {@code synaxis.max-players}, the capacity of the shard, and 5 joins a tick. Without a capacity,
     * or with one that is not a positive integer, the socket cap stays the engine's; {@code ServerConfig}
     * refuses one that is not an integer. Called by {@link Main} once the shard directory has written
     * the capacity of its channel, not before it like the others.
     *
     * @param properties the system properties already set
     * @return the properties and their values
     */
    static Map<String, String> gateDefaults(UnaryOperator<String> properties) {
        final Map<String, String> defaults = new LinkedHashMap<>();
        final String capacity = properties.apply(MAX_PLAYERS);
        if (properties.apply(MAX_CONNECTIONS) == null && capacity != null) {
            try {
                final int seats = Integer.parseInt(capacity);
                if (seats > 0) defaults.put(MAX_CONNECTIONS, Long.toString(Math.min(Integer.MAX_VALUE, 3L * seats)));
            } catch (NumberFormatException _) {
                // ServerConfig refuses it with its own message
            }
        }
        if (properties.apply(MAX_JOINS_PER_TICK) == null) defaults.put(MAX_JOINS_PER_TICK, "5");
        return defaults;
    }

    /**
     * Returns {@code synaxis.app.core-mode=on} when the shard mounts a map ({@code docs/DESIGN-M3.md}
     * {@code API-M3-006}): both map keys given and the command line left the core mode out. A map
     * shard loads no plugin; {@link Main} refuses one given another core mode, and one given a single
     * map key.
     *
     * @param properties the system properties already set
     * @return the property and its value, or nothing
     */
    static Map<String, String> mapDefaults(UnaryOperator<String> properties) {
        if (properties.apply(MAP_ROOT) == null || properties.apply(MAP_BUILD) == null
                || properties.apply(CoreMode.PROPERTY) != null)
            return Map.of();
        return Map.of(CoreMode.PROPERTY, "on");
    }
}
