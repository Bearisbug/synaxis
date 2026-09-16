package io.github.bearisbug.synaxis.game.rts;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.plugin.DisconnectKeys;
import io.github.bearisbug.synaxis.plugin.PluginAnalytics;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.world.DimensionType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * One match: its map, its instance and its lifecycle (REQ-M2-011, state machine in
 * {@code docs/DESIGN-M2.md} §11.3).
 * <p>
 * M2 runs a single match, built while the plugin is enabled and therefore before the server binds
 * its port. Everything here runs on the thread that enables the plugin, synchronously: the
 * scheduler only advances inside a tick and there is no tick thread yet, so a match that waited on
 * one would wait forever.
 */
final class Match {

    /** §17 {@code map.load}: the bounded wait the whole map generation gets. */
    private static final long LOAD_BUDGET_SECONDS = 30;

    /** §14: a match that ended with nowhere to move its players to is a match unavailable. */
    private static final Component MATCH_ENDED = Component.translatable(DisconnectKeys.MATCH_UNAVAILABLE);

    /** What a match is doing, §11.3. */
    enum State {
        CREATING, LOADING, RUNNING, ENDED
    }

    private final ServerProcess process;
    private final RegistryKey<DimensionType> dimensionType;
    private final PluginAnalytics analytics;
    private final Logger logger;

    private final UUID matchId = UUID.randomUUID();
    private final Key dimensionName = Key.key("synaxis", "match_" + UUID.randomUUID().toString().replace("-", ""));

    private State state = State.CREATING;
    private long createdAtNanos;
    private @Nullable MapPreset preset;
    private @Nullable NavGrid navGrid;
    private @Nullable InstanceContainer instance;

    Match(ServerProcess process, RegistryKey<DimensionType> dimensionType,
          PluginAnalytics analytics, Logger logger) {
        this.process = process;
        this.dimensionType = dimensionType;
        this.analytics = analytics;
        this.logger = logger;
    }

    State state() {
        return state;
    }

    UUID matchId() {
        return matchId;
    }

    /**
     * The instance the match is played in.
     *
     * @return the instance, null until the match is running
     */
    @Nullable InstanceContainer instance() {
        return instance;
    }

    /**
     * The navigation mesh generated together with the map.
     *
     * @return the navigation mesh, null until the match is running
     */
    @Nullable NavGrid navGrid() {
        return navGrid;
    }

    /**
     * Generates the map of the given preset and takes the match to {@link State#RUNNING}, or to
     * {@link State#ENDED} when it cannot ({@code map-invalid} for a preset that does not exist or
     * whose parameters are out of bounds, {@code load-failed} when the generation does not finish).
     * <p>
     * The caller is the startup sequence of {@code API-M2-010}, which refuses to bind the port
     * unless this left a running match behind.
     *
     * @param mapId the preset id, the value of {@code synaxis.app.map}
     */
    void load(String mapId) {
        if (state != State.CREATING) throw new IllegalStateException("a match only loads once, state=" + state);
        createdAtNanos = System.nanoTime();
        analytics.record("match_created", attributes("map_id", mapId, "dimension_name", dimensionName.asString()));

        final MapPreset selected = MapPreset.byId(mapId);
        if (selected == null) {
            endWith("map-invalid", "Unknown map preset: map_id=" + mapId
                    + ", known presets are " + MapPreset.knownIds());
            return;
        }
        final String invalid = selected.invalidParameters();
        if (invalid != null) {
            endWith("map-invalid", "Invalid generation parameters: map_id=" + mapId + ", " + invalid);
            return;
        }
        final DimensionType dimension = process.dimensionType().get(dimensionType);
        if (dimension == null || selected.height() > dimension.height()) {
            endWith("map-invalid", "Invalid generation parameters: map_id=" + mapId + ", height="
                    + selected.height() + " does not fit the world the host registered");
            return;
        }
        this.preset = selected;
        this.state = State.LOADING;

        final MapTerrain terrain = new MapTerrain(selected);
        this.navGrid = NavGrid.of(terrain);
        final InstanceContainer loading = new InstanceContainer(process, matchId, dimensionType, null, dimensionName);
        loading.setGenerator(new MapGenerator(terrain));
        // §15: the instance side of the view range, the three process wide ones are set by the host
        // before the process exists.
        loading.viewDistance(process.config().chunkViewDistance());

        final Runtime runtime = Runtime.getRuntime();
        final long heapBefore = runtime.totalMemory() - runtime.freeMemory();
        try {
            generateWholeMap(loading, selected);
        } catch (Exception e) {
            // The half built instance is never registered, so dropping the reference is all it takes
            logger.error("Map generation failed: match_id={} map_id={}", matchId, mapId, e);
            endWith("load-failed", null);
            return;
        }
        final long loadMs = (System.nanoTime() - createdAtNanos) / 1_000_000L;
        final long heapDeltaMb = Math.max(0, (runtime.totalMemory() - runtime.freeMemory() - heapBefore) / (1024 * 1024));

        // Nothing is ever loaded again: every chunk of the map is in memory, and a request outside
        // it must answer "there is no such chunk" instead of generating a 1025th one (REQ-M2-011).
        loading.enableAutoChunkLoad(false);
        process.instance().registerInstance(loading);
        this.instance = loading;
        this.state = State.RUNNING;
        logger.info("Match running: match_id={} map_id={} dimension_name={} chunk_count={} load_ms={}",
                matchId, mapId, dimensionName.asString(), selected.chunkCount(), loadMs);
        analytics.record("match_started", attributes("map_id", mapId, "chunk_count", selected.chunkCount(),
                "load_ms", loadMs, "heap_delta_mb", heapDeltaMb));
    }

    /**
     * Ends a running match: everybody still in it is disconnected and the whole instance is thrown
     * away, chunk by chunk (§11.3 {@code running → ended}). Does nothing once ended.
     */
    void end() {
        if (state == State.ENDED) return;
        final InstanceContainer running = this.instance;
        if (running != null) {
            for (Player player : List.copyOf(running.getPlayers())) player.kick(MATCH_ENDED);
            process.instance().unregisterInstance(running);
            this.instance = null;
        }
        endWith("normal", null);
    }

    /**
     * The spawn point of the match: the walkable column closest to the middle of the map.
     *
     * @return where a player joining this match is put
     * @throws IllegalStateException if the match is not running
     */
    Pos spawn() {
        final MapPreset selected = this.preset;
        final NavGrid grid = this.navGrid;
        if (selected == null || grid == null) throw new IllegalStateException("the match has no map, state=" + state);
        final int centerX = selected.sizeX() / 2;
        final int centerZ = selected.sizeZ() / 2;
        for (int radius = 0; radius < Math.max(selected.sizeX(), selected.sizeZ()); radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    final int x = centerX + dx;
                    final int z = centerZ + dz;
                    if (grid.isWalkable(x, z))
                        return selected.clamp(new Pos(x + 0.5, grid.surfaceY(x, z), z + 0.5));
                }
            }
        }
        throw new IllegalStateException("the generated map has no walkable column at all");
    }

    private static void generateWholeMap(InstanceContainer loading, MapPreset selected) throws Exception {
        final List<CompletableFuture<?>> chunks = new ArrayList<>(selected.chunkCount());
        for (int chunkX = 0; chunkX < selected.sizeX() / 16; chunkX++) {
            for (int chunkZ = 0; chunkZ < selected.sizeZ() / 16; chunkZ++) {
                chunks.add(loading.loadChunk(chunkX, chunkZ));
            }
        }
        // Generation and its callbacks run on virtual threads, not on a tick: this bounded wait
        // completes in a process that has not ticked once (API-M2-010 step 4).
        CompletableFuture.allOf(chunks.toArray(CompletableFuture[]::new))
                .get(LOAD_BUDGET_SECONDS, TimeUnit.SECONDS);
    }

    private void endWith(String reason, @Nullable String error) {
        if (error != null) logger.error("{}", error);
        final long durationS = (System.nanoTime() - createdAtNanos) / 1_000_000_000L;
        this.state = State.ENDED;
        analytics.record("match_ended", attributes("reason", reason, "duration_s", durationS));
    }

    private Map<String, Object> attributes(Object... keysAndValues) {
        final Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("match_id", matchId.toString());
        for (int i = 0; i < keysAndValues.length; i += 2) {
            attributes.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return attributes;
    }
}
