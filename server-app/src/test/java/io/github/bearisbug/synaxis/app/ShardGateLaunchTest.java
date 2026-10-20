package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.app.ConfigFileLaunchTest.Launched;
import io.github.bearisbug.synaxis.app.shard.ShardDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The connection gate of a shard ({@code docs/DESIGN-PROD.md} {@code API-PROD-003}) in the effective
 * settings a real JVM logs in step (2) of {@link Main}: 3 sockets a seat of {@code synaxis.max-players}
 * and 5 joins a tick. The server application keeps the engine's 1,024 sockets and takes any number of
 * joins a tick.
 */
public class ShardGateLaunchTest {

    private static String[] launch(String... more) throws Exception {
        final String[] base = {"-Dsynaxis.app.log=stdout", "-Dsynaxis.app.auth=offline",
                "-Dsynaxis.app.port=0", "-Dsynaxis.app.metrics.bind=127.0.0.1:0"};
        final String[] all = new String[base.length + more.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(more, 0, all, base.length, more.length);
        return all;
    }

    @Test
    public void aShardHoldsThreeSocketsASeatAndTakesFiveJoinsATick(@TempDir Path directory) throws Exception {
        try (Launched shard = Launched.start(directory, ShardMain.class, launch("-Dsynaxis.max-players=200",
                "-Dsynaxis.app.core-mode=on"))) {
            final String log = shard.awaitLine("listening on");
            assertTrue(log.contains("maxConnections=600,"), log);
            assertTrue(log.contains("maxJoinsPerTick=5,"), log);
        }
        try (Launched app = Launched.start(directory, Main.class, launch("-Dsynaxis.max-players=200",
                "-Dsynaxis.app.core-mode=on"))) {
            final String log = app.awaitLine("listening on");
            assertTrue(log.contains("maxConnections=1024,"), log);
            assertTrue(log.contains("maxJoinsPerTick=0,"), log);
        }
    }

    /**
     * In the directory mode the capacity comes from the shard directory alone, written in step (1):
     * {@code r0-c1} of capacity 200 in {@code shards.json}, no {@code synaxis.max-players} on the
     * command line, holds 600 sockets. The release is not there, so the shard refuses in step (3),
     * after step (2) logged its effective settings and before step (4) opens the store; no database
     * is needed.
     */
    @Test
    public void aShardInTheDirectoryModeHoldsThreeSocketsASeatOfItsChannel(@TempDir Path directory) throws Exception {
        Files.copy(Path.of(Objects.requireNonNull(getClass().getResource("/shards/shards.json")).toURI()),
                Files.createDirectories(directory.resolve("maps/fixture")).resolve(ShardDirectory.FILE_NAME));
        try (Launched shard = Launched.start(directory, ShardMain.class, launch("-Dsynaxis.map.root=maps/fixture",
                "-Dsynaxis.map.build=fixture-0123456789ab-p776", "-Dsynaxis.app.store=postgres",
                "-Dsynaxis.app.shard-id=r0-c1"))) {
            assertEquals(1, shard.awaitExit(), shard.log());
            final String log = shard.log();
            assertTrue(log.contains("maxPlayers=200,"), log);
            assertTrue(log.contains("maxConnections=600,"), log);
            assertTrue(log.contains("maxJoinsPerTick=5,"), log);
        }
    }

    /**
     * {@code synaxis.properties} over the defaults of the gate: {@link Main} applies the file as it is
     * initialised, before step (1) fills what is left, so a file giving 200 seats, 450 sockets and 2
     * joins a tick holds 450 sockets and takes 2 joins, not 600 and 5.
     */
    @Test
    public void aShardTakesTheGateFromItsConfigFileOverItsDefaults(@TempDir Path directory) throws Exception {
        ConfigFileTest.write(directory.resolve(ConfigFile.DEFAULT_FILE), """
                synaxis.max-players=200
                synaxis.max-connections=450
                synaxis.max-joins-per-tick=2
                """);
        try (Launched shard = Launched.start(directory, ShardMain.class, launch("-Dsynaxis.app.core-mode=on"))) {
            final String log = shard.awaitLine("listening on");
            assertTrue(log.contains("keys=3 overridden_by_command_line=[]"), log);
            assertTrue(log.contains("maxConnections=450,"), log);
            assertTrue(log.contains("maxJoinsPerTick=2,"), log);
        }
    }
}
