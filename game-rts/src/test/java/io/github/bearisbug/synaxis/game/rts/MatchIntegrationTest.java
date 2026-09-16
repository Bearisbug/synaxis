package io.github.bearisbug.synaxis.game.rts;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.world.DimensionType;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The match lifecycle of REQ-M2-011 (state machine in {@code docs/DESIGN-M2.md} §11.3).
 * <p>
 * Not one of these tests ticks the process, which is the point: the whole map is generated on the
 * calling thread, because in production that thread is the one starting the server and the tick
 * thread does not exist until the port is bound (API-M2-010 step 4).
 */
@EnvTest
class MatchIntegrationTest {

    private final RecordedEvents events = new RecordedEvents();

    private Match match(Env env) {
        return new Match(env.process(), MatchSupport.registerMatchDimension(env), events,
                LoggerFactory.getLogger(MatchIntegrationTest.class));
    }

    @Test
    void theWholeMapIsGeneratedBeforeTheMatchRuns(Env env) {
        final Match match = match(env);
        match.load(MapPreset.SMALL.id());

        assertEquals(Match.State.RUNNING, match.state());
        final InstanceContainer instance = match.instance();
        assertNotNull(instance);
        assertEquals(MapPreset.SMALL.chunkCount(), instance.getChunks().size(),
                "the match went running with part of its map missing");
        assertFalse(instance.hasEnabledAutoChunkLoad(),
                "the whole map is in memory, nothing may be loaded on demand any more");
        assertTrue(instance.isRegistered(), "a running match has to be registered, it is what the host asserts");
        assertEquals(env.process().config().chunkViewDistance(), instance.viewDistance());

        assertEquals(List.of("match_created", "match_started"), events.names());
        final Map<String, ?> started = events.last("match_started");
        assertNotNull(started);
        assertEquals(MapPreset.SMALL.chunkCount(), started.get("chunk_count"));
        assertEquals(match.matchId().toString(), started.get("match_id"));
    }

    @Test
    void theNavigationMeshComesOutOfTheSameGeneration(Env env) {
        final Match match = match(env);
        match.load(MapPreset.SMALL.id());

        final NavGrid grid = match.navGrid();
        final InstanceContainer instance = match.instance();
        assertNotNull(grid);
        assertNotNull(instance);
        // What the mesh says about a column is what the blocks of that column say
        for (int x = 0; x < MapPreset.SMALL.sizeX(); x += 7) {
            for (int z = 0; z < MapPreset.SMALL.sizeZ(); z += 5) {
                final boolean groundIsClear = Block.AIR.equals(instance.getBlock(x, MapTerrain.GROUND_HEIGHT, z));
                assertEquals(groundIsClear, grid.isWalkable(x, z),
                        "column " + x + "," + z + " disagrees with the blocks generated for it");
                assertEquals(Block.STONE, instance.getBlock(x, grid.surfaceY(x, z) - 1, z),
                        "column " + x + "," + z + " has nothing to stand on at its own surface height");
            }
        }
    }

    // The map of the game, not the small one the rest of these tests use: 512x512 in 1,024 chunks,
    // budgeted by §15 at under 3 seconds. The measured numbers go into the log so that a slowdown
    // that stays inside the budget is still visible.
    @Test
    void theFullSizeMapGenerates(Env env) {
        final Match match = match(env);
        match.load(MapPreset.DEFAULT.id());

        assertEquals(Match.State.RUNNING, match.state());
        final InstanceContainer instance = match.instance();
        assertNotNull(instance);
        assertEquals(1024, instance.getChunks().size());
        assertEquals(Block.STONE, instance.getBlock(500, MapTerrain.GROUND_HEIGHT - 1, 500),
                "the far corner of the map is not built");
        final Map<String, ?> started = events.last("match_started");
        assertNotNull(started);
        assertEquals(1024, started.get("chunk_count"));
        LoggerFactory.getLogger(MatchIntegrationTest.class).info(
                "Full size map: chunk_count={} load_ms={} heap_delta_mb={}",
                started.get("chunk_count"), started.get("load_ms"), started.get("heap_delta_mb"));
        assertTrue((Long) started.get("load_ms") < 3_000,
                "generating the map took load_ms=" + started.get("load_ms") + ", the §15 budget is 3 s");
    }

    @Test
    void theSpawnPointStandsOnTheMap(Env env) {
        final Match match = match(env);
        match.load(MapPreset.SMALL.id());

        final Pos spawn = match.spawn();
        final NavGrid grid = match.navGrid();
        assertNotNull(grid);
        assertTrue(MapPreset.SMALL.contains(spawn.x(), spawn.z()), spawn.toString());
        assertTrue(grid.isWalkable(spawn.blockX(), spawn.blockZ()), spawn.toString());
    }

    @Test
    void anUnknownPresetEndsTheMatchWithoutBuildingAnything(Env env) {
        final Match match = match(env);
        match.load("no-such-map");

        assertEquals(Match.State.ENDED, match.state());
        assertNull(match.instance(), "an invalid map left a half built world behind");
        assertEquals(List.of("match_created", "match_ended"), events.names());
        final Map<String, ?> ended = events.last("match_ended");
        assertNotNull(ended);
        assertEquals("map-invalid", ended.get("reason"));
        assertEquals(0, registeredInstances(env), "an invalid map registered an instance anyway");
    }

    @Test
    void aPresetThatDoesNotFitTheWorldEndsTheMatch(Env env) {
        // A shallower world than the preset asks for: same outcome as any other invalid parameter
        final RegistryKey<DimensionType> shallow = env.process().dimensionType().register(
                Key.key("synaxis:too_shallow"),
                DimensionType.builder().minY(0).height(16).logicalHeight(16).build());
        final Match match = new Match(env.process(), shallow, events,
                LoggerFactory.getLogger(MatchIntegrationTest.class));

        match.load(MapPreset.SMALL.id());

        assertEquals(Match.State.ENDED, match.state());
        assertNull(match.instance());
        final Map<String, ?> ended = events.last("match_ended");
        assertNotNull(ended);
        assertEquals("map-invalid", ended.get("reason"));
    }

    @Test
    void aColumnOutsideTheMapIsNeverGenerated(Env env) {
        final Match match = match(env);
        match.load(MapPreset.SMALL.id());
        final InstanceContainer instance = match.instance();
        assertNotNull(instance);

        final int outsideChunk = MapPreset.SMALL.sizeX() / 16;
        assertNull(instance.loadOptionalChunk(outsideChunk, 0).join(),
                "a column outside the map was generated anyway");
        assertEquals(MapPreset.SMALL.chunkCount(), instance.getChunks().size());
    }

    @Test
    void endingTheMatchThrowsTheWholeInstanceAway(Env env) {
        final Match match = match(env);
        match.load(MapPreset.SMALL.id());
        final InstanceContainer instance = match.instance();
        assertNotNull(instance);
        final Player player = env.createPlayer(instance, match.spawn());
        env.tick();

        match.end();

        assertEquals(Match.State.ENDED, match.state());
        assertNull(match.instance());
        assertFalse(instance.isRegistered(), "the instance of an ended match is still registered");
        assertEquals(0, registeredInstances(env));
        assertTrue(player.isRemoved(), "a player was left behind in an ended match");
        final Map<String, ?> ended = events.last("match_ended");
        assertNotNull(ended);
        assertEquals("normal", ended.get("reason"));
    }

    private static int registeredInstances(Env env) {
        return env.process().instance().getInstances().size();
    }
}
