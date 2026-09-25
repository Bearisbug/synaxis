package io.github.bearisbug.synaxis.baker;

import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.synx.SynxBlockEntity;
import io.github.bearisbug.synaxis.synx.SynxChunkRecord;
import io.github.bearisbug.synaxis.tag.Tag;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static io.github.bearisbug.synaxis.baker.BakeTesting.list;
import static io.github.bearisbug.synaxis.baker.BakeTesting.record;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The command line on the vanilla fixture save of {@code anvil/}: the handler namespaces a data marker may
 * name are the {@link BlockHandler} services on the class path, here the two of
 * {@code src/test/resources/META-INF/services}.
 */
public class BakerMainTest {
    @TempDir
    Path temp;

    public static final class DoorHandler implements BlockHandler {
        @Override
        public Key getKey() {
            return Key.key("synaxis:door");
        }
    }

    public static final class LootHandler implements BlockHandler {
        @Override
        public Key getKey() {
            return Key.key("synaxis:loot");
        }

        @Override
        public List<Tag<?>> getBlockEntityTags() {
            return List.of(Tag.String("table"));
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
}
