package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.app.ConfigFileLaunchTest.Launched;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static io.github.bearisbug.synaxis.app.ConfigFileLaunchTest.freePort;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The refusals of a map shard in a real JVM ({@code docs/DESIGN-M3.md} {@code API-M3-006}, §14; §19
 * 「地图配置写错时拒启」「视距越界配置被拒」): one line {@code Refusing to start: …} naming the key,
 * exit code 1, the port never bound and no {@code overlay/} directory. The map keys point at a
 * directory that does not exist: every one of these refusals comes before the release is read.
 */
public class ShardLaunchTest {

    private static final String ROOT = "-Dsynaxis.map.root=maps/fixture";
    private static final String BUILD = "-Dsynaxis.map.build=fixture-0123456789ab-p776";

    private static String[] launch(String... more) throws Exception {
        final String[] base = {"-Dsynaxis.app.log=stdout", "-Dsynaxis.app.auth=offline",
                "-Dsynaxis.app.port=" + freePort(), "-Dsynaxis.app.metrics.bind=127.0.0.1:0"};
        final String[] all = new String[base.length + more.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(more, 0, all, base.length, more.length);
        return all;
    }

    private static void assertRefused(Path directory, Class<?> launcher, String refusal, String... properties)
            throws Exception {
        try (Launched launched = Launched.start(directory, launcher, launch(properties))) {
            assertEquals(1, launched.awaitExit(), launched.log());
            final String log = launched.log();
            assertTrue(log.contains("Refusing to start: " + refusal), log);
            assertFalse(log.contains("listening on"), log);
        }
        assertFalse(Files.exists(directory.resolve("overlay")), "nothing is written");
    }

    @Test
    public void aMapBuildWithoutAMapRootIsRefused(@TempDir Path directory) throws Exception {
        assertRefused(directory, ShardMain.class,
                "synaxis.map.root and synaxis.map.build mount a map together, only synaxis.map.build is given", BUILD);
    }

    @Test
    public void theServerAppDoesNotMountAMap(@TempDir Path directory) throws Exception {
        assertRefused(directory, Main.class, "synaxis.map.root and synaxis.map.build mount a map, which only the shard "
                + "host does (bin/server-shard), not bin/server-app", ROOT, BUILD);
    }

    @Test
    public void aMapShardWithAnotherCoreModeIsRefused(@TempDir Path directory) throws Exception {
        assertRefused(directory, ShardMain.class, "a map shard loads no plugin, synaxis.app.core-mode must be on, not auto",
                ROOT, BUILD, "-Dsynaxis.app.core-mode=auto");
    }

    @Test
    public void anUpperCaseBooleanIsRefused(@TempDir Path directory) throws Exception {
        assertRefused(directory, ShardMain.class, "Property 'synaxis.world-writable' is not true or false: \"TRUE\"",
                ROOT, BUILD, "-Dsynaxis.world-writable=TRUE");
    }

    @Test
    public void aFlushIntervalOfZeroIsRefused(@TempDir Path directory) throws Exception {
        assertRefused(directory, ShardMain.class, "Property 'synaxis.overlay.flush-interval-s' is not from 1 to 60: 0",
                ROOT, BUILD, "-Dsynaxis.overlay.flush-interval-s=0");
    }

    @Test
    public void aMapShardSeesAtMostSixteenChunks(@TempDir Path directory) throws Exception {
        assertRefused(directory, ShardMain.class, "synaxis.chunk-view-distance=17 is above 16", ROOT, BUILD,
                "-Dsynaxis.chunk-view-distance=17");
    }

    @Test
    public void theSimulationDistanceDoesNotExceedTheViewDistance(@TempDir Path directory) throws Exception {
        assertRefused(directory, ShardMain.class, "synaxis.simulation-distance=13 is not from 1 to the chunk view "
                + "distance 12: the simulation distance cannot exceed the view distance", ROOT, BUILD,
                "-Dsynaxis.simulation-distance=13");
    }

    /** Without a map, the view distance of a shard is not capped. */
    @Test
    public void aShardWithoutAMapSeesSeventeenChunks(@TempDir Path directory) throws Exception {
        try (Launched shard = Launched.start(directory, ShardMain.class, launch("-Dsynaxis.chunk-view-distance=17",
                "-Dsynaxis.app.core-mode=on"))) {
            final String log = shard.awaitLine("listening on");
            assertFalse(log.contains("Refusing to start"), log);
        }
        assertFalse(Files.exists(directory.resolve("overlay")));
    }
}
