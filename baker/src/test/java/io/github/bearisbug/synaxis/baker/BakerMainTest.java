package io.github.bearisbug.synaxis.baker;

import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.synx.SynxBlockEntity;
import io.github.bearisbug.synaxis.synx.SynxChunkRecord;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.github.bearisbug.synaxis.baker.BakeTesting.events;
import static io.github.bearisbug.synaxis.baker.BakeTesting.list;
import static io.github.bearisbug.synaxis.baker.BakeTesting.packet;
import static io.github.bearisbug.synaxis.baker.BakeTesting.record;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The command line on the vanilla fixture save of {@code anvil/}: the handler namespaces a data marker may
 * name are the {@link BlockHandler} services on the class path, here the two the test sources list
 * ({@link DoorMarker} and {@link LootMarker}).
 */
public class BakerMainTest {
    @TempDir
    Path temp;

    /** A second handler with the key of {@link DoorMarker}. */
    public static final class SecondDoor implements BlockHandler {
        @Override
        public Key getKey() {
            return Key.key("synaxis:door");
        }
    }

    @Test
    public void theCommandLineBakesASaveWithDataMarkers() throws Exception {
        final Path world = Path.of(BakerMainTest.class.getResource("anvil/world").toURI());
        final int exit = BakerMain.run(new String[]{"bake", "--map-id", "marked", "--source", "anvil:" + world,
                "--bounds", "0,0,0,0", "--out", temp.resolve("maps").toString(), "--threads", "2",
                "--logs", temp.resolve("logs").toString()});

        assertEquals(Baker.EXIT_PUBLISHED, exit);
        final SynxChunkRecord record = record(list(temp.resolve("maps").resolve("marked")).getFirst(), 0, 0);
        final List<String> handlers = record.blockEntities().stream().map(SynxBlockEntity::handler)
                .filter(handler -> !handler.isEmpty()).sorted().toList();
        assertEquals(List.of("synaxis:door", "synaxis:loot"), handlers);
        final Block chest = packet(record).chunkData().blockEntities().values().stream()
                .filter(block -> block.name().equals("minecraft:chest")).findFirst().orElseThrow();
        assertEquals(CompoundBinaryTag.builder().putString("table", "t1").build(), chest.nbt(),
                "the loot marker shows the client its table and nothing else");
    }

    /**
     * API-M3-003: two handlers with one key on the class path exit with 1, naming the key and both classes.
     * The second one is listed by a class loader over {@code classes/}, which the service lookup reads through
     * the thread's context class loader, after the class path of the test.
     */
    @Test
    public void twoHandlersWithOneKeyExitOne() throws Exception {
        final Path world = Path.of(BakerMainTest.class.getResource("anvil/world").toURI());
        final Path classes = temp.resolve("classes");
        Files.writeString(Files.createDirectories(classes.resolve("META-INF/services")).resolve(BlockHandler.class.getName()),
                SecondDoor.class.getName() + "\n");
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final PrintStream stderr = System.err;
        final Thread thread = Thread.currentThread();
        final ClassLoader loader = thread.getContextClassLoader();
        final int exit;
        try (URLClassLoader withSecondDoor = new URLClassLoader(new URL[]{classes.toUri().toURL()}, loader)) {
            thread.setContextClassLoader(withSecondDoor);
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            exit = BakerMain.run(new String[]{"bake", "--map-id", "marked", "--source", "anvil:" + world,
                    "--bounds", "0,0,0,0", "--out", temp.resolve("maps").toString(), "--threads", "2",
                    "--logs", temp.resolve("logs").toString()});
        } finally {
            System.setErr(stderr);
            thread.setContextClassLoader(loader);
        }

        final String message = err.toString(StandardCharsets.UTF_8);
        assertEquals(BakerMain.EXIT_USAGE, exit, message);
        assertTrue(message.contains("synaxis:door"), message);
        assertTrue(message.contains(DoorMarker.class.getName()), message);
        assertTrue(message.contains(SecondDoor.class.getName()), message);
        assertFalse(Files.exists(temp.resolve("maps")));
        assertFalse(Files.exists(temp.resolve("logs")));
    }

    /** API-M3-003: {@code --min-y} is a multiple of 16 and belongs to an Anvil source; otherwise exit code 1. */
    @Test
    public void aMinYTheCommandLineCannotUseExitsOne() throws Exception {
        final Path world = Path.of(BakerMainTest.class.getResource("anvil/world").toURI());
        assertEquals(BakerMain.EXIT_USAGE, BakerMain.run(new String[]{"bake", "--map-id", "m", "--source", "anvil:" + world,
                "--bounds", "0,0,0,0", "--out", temp.resolve("maps").toString(), "--min-y", "-60"}));
        assertEquals(BakerMain.EXIT_USAGE, BakerMain.run(new String[]{"bake", "--map-id", "m", "--source", "generator:synthetic:1",
                "--bounds", "0,0,0,0", "--out", temp.resolve("maps").toString(), "--min-y", "0"}));
        assertEquals(List.of(), list(temp));
    }

    /** The window moves with {@code --min-y}: from y 0 the fixture's bedrock at y -64 is outside, exit code 2. */
    @Test
    public void theCommandLinePassesMinYToTheSource() throws Exception {
        final Path world = Path.of(BakerMainTest.class.getResource("anvil/world").toURI());
        assertEquals(Baker.EXIT_SOURCE, BakerMain.run(new String[]{"bake", "--map-id", "m", "--source", "anvil:" + world,
                "--bounds", "0,0,0,0", "--out", temp.resolve("maps").toString(), "--min-y", "0",
                "--logs", temp.resolve("logs").toString()}));
    }

    private record Run(int exitCode, String err) {
    }

    /** The command line with its stdout dropped and its stderr kept. */
    private static Run run(String... args) {
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final PrintStream stdout = System.out, stderr = System.err;
        try {
            System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            return new Run(BakerMain.run(args), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(stdout);
            System.setErr(stderr);
        }
    }

    /**
     * §9 map_id, API-M3-003: an id that is not {@code [a-z0-9][a-z0-9_-]{0,31}} names no directory to bake into,
     * so it exits with 1 before anything is written.
     */
    @Test
    public void aMapIdThatIsNotOneExitsOneAndWritesNothing() throws Exception {
        for (String mapId : List.of("castle/v2", "..", "Arena 2", "", "-arena", "a".repeat(33))) {
            final Run run = run("bake", "--map-id", mapId, "--source", "generator:synthetic:1", "--bounds", "0,0,0,0",
                    "--out", temp.resolve("maps").toString(), "--logs", temp.resolve("logs").toString());
            assertEquals(BakerMain.EXIT_USAGE, run.exitCode(), mapId);
            assertTrue(run.err().startsWith("baker: --map-id is [a-z0-9][a-z0-9_-]{0,31}"), run.err());
        }
        assertEquals(List.of(), list(temp));
    }

    /** A map_id of 32 characters bakes; the started event records the {@code --source} as given (§20 source_spec). */
    @Test
    public void aMapIdOfThirtyTwoCharactersBakesAndTheEventRecordsTheSource() throws Exception {
        final String mapId = "a".repeat(31) + "9";
        final Run run = run("bake", "--map-id", mapId, "--source", "generator:synthetic:1", "--bounds", "0,0,0,0",
                "--threads", "1", "--out", temp.resolve("maps").toString(), "--logs", temp.resolve("logs").toString());

        assertEquals(Baker.EXIT_PUBLISHED, run.exitCode(), run.err());
        assertEquals(1, list(temp.resolve("maps").resolve(mapId)).size());
        final String started = events(temp).getFirst();
        assertTrue(started.contains("\"event\":\"map_build_started\"")
                && started.contains("\"source_spec\":\"generator:synthetic:1\""), started);
    }

    /**
     * API-M3-003: an option the command does not take (misspelled, or another command's), one given twice,
     * {@code --dimension} with a generator source or as no key, and a number that is not one exit with 1 and
     * name what is wrong, before anything is written.
     */
    @Test
    public void anOptionTheCommandCannotTakeExitsOneAndWritesNothing() throws Exception {
        final String world = "anvil:" + Path.of(BakerMainTest.class.getResource("anvil/world").toURI());
        final String maps = temp.resolve("maps").toString(), logs = temp.resolve("logs").toString();
        final List<List<String>> refused = List.of(
                List.of("unknown option --bound for bake",
                        "bake", "--map-id", "m", "--source", world, "--bound", "0,0,0,0", "--out", maps, "--logs", logs),
                List.of("--threads is given twice", "bake", "--map-id", "m", "--source", world, "--bounds", "0,0,0,0",
                        "--threads", "1", "--threads", "2", "--out", maps, "--logs", logs),
                List.of("unknown option --samples for verify", "verify", "--build", maps, "--samples", "8"),
                List.of("--dimension applies to an anvil: source only", "bake", "--map-id", "m", "--source",
                        "generator:synthetic:1", "--dimension", "minecraft:overworld", "--out", maps, "--logs", logs),
                List.of("--dimension is a key such as minecraft:overworld, got Minecraft:Overworld", "bake", "--map-id",
                        "m", "--source", world, "--dimension", "Minecraft:Overworld", "--out", maps, "--logs", logs),
                List.of("--threads takes whole numbers, got eight", "bake", "--map-id", "m", "--source", world,
                        "--threads", "eight", "--out", maps, "--logs", logs),
                List.of("--bounds takes whole numbers, got a", "bake", "--map-id", "m", "--source", world,
                        "--bounds", "0,0,a,0", "--out", maps, "--logs", logs),
                List.of("bounds 3,0,0,0 are empty", "bake", "--map-id", "m", "--source", world,
                        "--bounds", "3,0,0,0", "--out", maps, "--logs", logs),
                List.of("takes a whole number seed, got generator:synthetic:seven", "bake", "--map-id", "m",
                        "--source", "generator:synthetic:seven", "--out", maps, "--logs", logs));
        for (List<String> refusal : refused) {
            final Run run = run(refusal.subList(1, refusal.size()).toArray(String[]::new));
            assertEquals(BakerMain.EXIT_USAGE, run.exitCode(), run.err());
            assertTrue(run.err().startsWith("baker: ") && run.err().contains(refusal.getFirst()), run.err());
        }
        assertEquals(List.of(), list(temp));
    }
}
