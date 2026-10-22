package io.github.bearisbug.synaxis.baker.anvil;

import io.github.bearisbug.synaxis.baker.AnvilSource;
import io.github.bearisbug.synaxis.baker.BakeTesting;
import io.github.bearisbug.synaxis.baker.Baker;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.network.packet.server.play.data.ChunkData;
import io.github.bearisbug.synaxis.synx.SynxBlockEntity;
import io.github.bearisbug.synaxis.synx.SynxChunkRecord;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import io.github.bearisbug.synaxis.tag.Tag;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.ByteArrayBinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.DoubleBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.bearisbug.synaxis.baker.BakeTesting.REGISTRIES;
import static io.github.bearisbug.synaxis.baker.BakeTesting.list;
import static io.github.bearisbug.synaxis.baker.BakeTesting.packet;
import static io.github.bearisbug.synaxis.baker.BakeTesting.record;
import static io.github.bearisbug.synaxis.baker.BakeTesting.request;
import static io.github.bearisbug.synaxis.baker.BakeTesting.sections;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The baker on the vanilla 26.2 fixture save (REQ-M3-003, REQ-M3-020 to REQ-M3-022; §19 of
 * {@code docs/DESIGN-M3.md}: the DataVersion gate, proto chunks, entities, source light parity, data markers).
 * The fixture's only full chunk is 0,0; chunk 3,0 stopped at {@code initialize_light}.
 */
public class AnvilBakeTest {
    private static final Key OVERWORLD = Key.key("minecraft:overworld");
    private static final SynxManifest.Bounds CHUNK_0_0 = new SynxManifest.Bounds(0, 0, 0, 0);
    private static final Map<String, BlockHandler> HANDLERS = Map.of(
            "synaxis:door", new BakeTesting.TestHandler(Key.key("synaxis:door"), List.of()),
            "synaxis:loot", new BakeTesting.TestHandler(Key.key("synaxis:loot"), List.of(Tag.String("table"))));

    @TempDir
    Path temp;

    private static Baker.Result bake(Path root, Path world, SynxManifest.@Nullable Bounds bounds,
                                     Map<String, BlockHandler> handlers) throws IOException {
        return bake(root, world, bounds, AnvilSource.DEFAULT_MIN_Y, handlers);
    }

    private static Baker.Result bake(Path root, Path world, SynxManifest.@Nullable Bounds bounds, int minY,
                                     Map<String, BlockHandler> handlers) throws IOException {
        try (AnvilSource source = AnvilSource.open(world, OVERWORLD, bounds, minY, REGISTRIES, handlers::containsKey)) {
            return Baker.bake(request("anvil", source, root, 2, handlers::get));
        }
    }

    private Baker.Result bake(Path world, SynxManifest.@Nullable Bounds bounds) throws IOException {
        return bake(temp, world, bounds, HANDLERS);
    }

    private void assertNothingWritten() throws IOException {
        assertEquals(List.of(), list(temp.resolve("maps")));
        assertFalse(Files.exists(temp.resolve("logs")), "a refused source writes no event");
    }

    @Test
    public void bakesTheFullChunkAndRecordsTheSource() throws Exception {
        final Baker.Result result = bake(RegionFixtures.world(), CHUNK_0_0);

        assertEquals(Baker.EXIT_PUBLISHED, result.exitCode(), result.message());
        final SynxManifest manifest = result.manifest();
        assertNotNull(manifest);
        assertEquals(new SynxManifest.World(-64, 256), manifest.world(), "the default window, y -64 to 191");
        assertEquals("anvil", manifest.source().kind());
        assertEquals(4903, manifest.source().dataVersion());
        assertEquals(1.0, manifest.source().lightCoverage());
        assertEquals(1, manifest.verify().samples());
        assertEquals(0, manifest.verify().diffs());
        assertNotNull(manifest.verify().sourceLightDiff());
        assertEquals(16, sections(record(result.directory(), 0, 0)).size());
    }

    /** §19: one stone above the window refuses the source, naming the chunk, the block, its y and the window. */
    @Test
    public void aBlockAboveTheWindowExitsTwo() throws Exception {
        final Path world = RegionFixtures.copyWorld(temp.resolve("save"));
        // section 12 is y 192..207, all air in the fixture; make it all stone
        RegionFixtures.rewriteChunk(world.resolve(RegionFixtures.REGION), 0, 0, chunk -> {
            final ListBinaryTag.Builder<CompoundBinaryTag> sections = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
            for (CompoundBinaryTag section : chunk.getList("sections").stream().map(CompoundBinaryTag.class::cast).toList()) {
                sections.add(section.getByte("Y") != 12 ? section : section.put("block_states", CompoundBinaryTag.builder()
                        .put("palette", ListBinaryTag.from(List.of(CompoundBinaryTag.builder()
                                .putString("Name", "minecraft:stone").build())))
                        .build()));
            }
            return chunk.put("sections", sections.build());
        });
        final Baker.Result result = bake(world, CHUNK_0_0);

        assertEquals(Baker.EXIT_SOURCE, result.exitCode(), result.message());
        assertTrue(result.message().contains("chunk 0, 0: block minecraft:stone at y 192")
                && result.message().contains("y -64 to 191"), result.message());
        assertNothingWritten();
    }

    /** §19: a block entity above the window is refused even where the block itself is air. */
    @Test
    public void aBlockEntityAboveTheWindowExitsTwo() throws Exception {
        final Path world = RegionFixtures.copyWorld(temp.resolve("save"));
        RegionFixtures.rewriteChunk(world.resolve(RegionFixtures.REGION), 0, 0, chunk -> {
            final List<CompoundBinaryTag> blockEntities = new ArrayList<>(chunk.getList("block_entities").stream()
                    .map(CompoundBinaryTag.class::cast).toList());
            blockEntities.add(CompoundBinaryTag.builder().putString("id", "minecraft:chest")
                    .putInt("x", 3).putInt("y", 200).putInt("z", 3).build());
            return chunk.put("block_entities", ListBinaryTag.from(blockEntities));
        });
        final Baker.Result result = bake(world, CHUNK_0_0);

        assertEquals(Baker.EXIT_SOURCE, result.exitCode(), result.message());
        assertTrue(result.message().contains("chunk 0, 0: block entity minecraft:chest at y 200"), result.message());
        assertNothingWritten();
    }

    /** §19: a static entity above the window is refused. */
    @Test
    public void anEntityAboveTheWindowExitsTwo() throws Exception {
        final Path world = RegionFixtures.copyWorld(temp.resolve("save"));
        RegionFixtures.rewriteChunk(world.resolve(RegionFixtures.ENTITIES), 0, 0, chunk -> {
            final List<CompoundBinaryTag> entities = new ArrayList<>();
            for (CompoundBinaryTag entity : chunk.getList("Entities").stream().map(CompoundBinaryTag.class::cast).toList()) {
                final ListBinaryTag pos = entity.getList("Pos");
                entities.add(!entity.getString("id").equals("minecraft:text_display") ? entity
                        : entity.put("Pos", ListBinaryTag.from(List.of(pos.get(0), DoubleBinaryTag.doubleBinaryTag(250.5), pos.get(2)))));
            }
            return chunk.put("Entities", ListBinaryTag.from(entities));
        });
        final Baker.Result result = bake(world, CHUNK_0_0);

        assertEquals(Baker.EXIT_SOURCE, result.exitCode(), result.message());
        assertTrue(result.message().contains("chunk 0, 0: entity minecraft:text_display at y 250"), result.message());
        assertNothingWritten();
    }

    /** §19: moving the window up to y 0 leaves the fixture's bedrock at y -64 below it. */
    @Test
    public void aWindowAboveTheBedrockExitsTwo() throws Exception {
        final Baker.Result result = bake(temp, RegionFixtures.world(), CHUNK_0_0, 0, HANDLERS);

        assertEquals(Baker.EXIT_SOURCE, result.exitCode(), result.message());
        assertTrue(result.message().contains("chunk 0, 0: block minecraft:bedrock at y -64")
                && result.message().contains("y 0 to 255"), result.message());
        assertNothingWritten();
    }

    /** §19 REQ-M3-021: one nibble of the source's sky light changed is one more difference, and still published. */
    @Test
    public void theSourceLightParityOnlyReports() throws Exception {
        final Baker.Result original = bake(temp.resolve("original"), RegionFixtures.world(), CHUNK_0_0, HANDLERS);
        assertEquals(Baker.EXIT_PUBLISHED, original.exitCode(), original.message());
        final long before = original.manifest().verify().sourceLightDiff();

        final Path world = RegionFixtures.copyWorld(temp.resolve("edited"));
        // (0, -50, 0) is open sky in section -4, level 15; make it 14
        RegionFixtures.rewriteChunk(world.resolve(RegionFixtures.REGION), 0, 0, chunk -> {
            final ListBinaryTag.Builder<CompoundBinaryTag> sections = ListBinaryTag.builder(
                    BinaryTagTypes.COMPOUND);
            for (CompoundBinaryTag section : chunk.getList("sections").stream().map(CompoundBinaryTag.class::cast).toList()) {
                if (section.getByte("Y") == -4) {
                    final byte[] sky = section.getByteArray("SkyLight").clone();
                    final int index = (-50 & 15) << 8;
                    assertEquals(15, sky[index >> 1] & 0x0F, "the fixture has sky 15 there");
                    sky[index >> 1] = (byte) ((sky[index >> 1] & 0xF0) | 14);
                    section = section.put("SkyLight", ByteArrayBinaryTag.byteArrayBinaryTag(sky));
                }
                sections.add(section);
            }
            return chunk.put("sections", sections.build());
        });
        final Baker.Result edited = bake(temp.resolve("edited-bake"), world, CHUNK_0_0, HANDLERS);

        assertEquals(Baker.EXIT_PUBLISHED, edited.exitCode(), edited.message());
        assertEquals(original.manifest().buildId(), edited.manifest().buildId(), "the source light is not baked in");
        assertEquals(before + 1, edited.manifest().verify().sourceLightDiff());
        assertTrue(BakeTesting.events(temp.resolve("edited-bake")).getLast().contains("\"source_light_diff\":" + (before + 1)),
                BakeTesting.events(temp.resolve("edited-bake")).toString());
    }

    /**
     * REQ-M3-021: the fixture's two data markers turn into air, which lights them and the blocks they stand
     * on differently from the source; those cells are left out, and the rest of the fixture matches.
     */
    @Test
    public void theSourceLightParityLeavesTheMarkersOut() throws Exception {
        final Baker.Result result = bake(RegionFixtures.world(), CHUNK_0_0);

        assertEquals(Baker.EXIT_PUBLISHED, result.exitCode(), result.message());
        assertEquals(0L, result.manifest().verify().sourceLightDiff());
    }

    /** §19: a chunk of another DataVersion refuses the source, exit code 2, before anything is written. */
    @Test
    public void anotherDataVersionExitsTwoAndNamesTheChunk() throws Exception {
        final Path world = RegionFixtures.copyWorld(temp.resolve("save"));
        RegionFixtures.rewriteChunk(world.resolve(RegionFixtures.REGION), 0, 0, chunk -> chunk.putInt("DataVersion", 4790));
        final Baker.Result result = bake(world, CHUNK_0_0);

        assertEquals(Baker.EXIT_SOURCE, result.exitCode());
        assertTrue(result.message().contains("chunk 0, 0") && result.message().contains("--forceUpgrade"), result.message());
        assertNothingWritten();
    }

    /** §19: a proto chunk inside the bounds is a missing source chunk. */
    @Test
    public void aProtoChunkExitsTwoAndNamesTheChunk() throws Exception {
        final Baker.Result result = bake(RegionFixtures.world(), new SynxManifest.Bounds(3, 0, 3, 0));

        assertEquals(Baker.EXIT_SOURCE, result.exitCode());
        assertTrue(result.message().contains("chunk 3, 0") && result.message().contains("initialize_light"),
                result.message());
        assertNothingWritten();
    }

    @Test
    public void aChunkMissingInsideTheBoundsExitsTwo() throws Exception {
        final Baker.Result result = bake(RegionFixtures.world(), null);

        assertEquals(Baker.EXIT_SOURCE, result.exitCode());
        assertTrue(result.message().contains("chunk 1, 0") && result.message().contains("missing"), result.message());
        assertNothingWritten();
    }

    /** §19: a marker naming a namespace no handler is registered for refuses the source. */
    @Test
    public void anUnregisteredMarkerNamespaceExitsTwo() throws Exception {
        final Baker.Result result = bake(temp, RegionFixtures.world(), CHUNK_0_0, Map.of("synaxis:loot", HANDLERS.get("synaxis:loot")));

        assertEquals(Baker.EXIT_SOURCE, result.exitCode());
        assertTrue(result.message().contains("5, -59, 5") && result.message().contains("synaxis:door"), result.message());
        assertNothingWritten();
    }

    /**
     * §19 REQ-M3-022: the marker blocks are air in segment ①, the blocks below them carry the handler and the
     * marker's NBT in segment ③, and the client sees only the tags the handler declares.
     */
    @Test
    public void dataMarkersBecomeSegmentThreeRecords() throws Exception {
        final Baker.Result result = bake(RegionFixtures.world(), CHUNK_0_0);
        assertEquals(Baker.EXIT_PUBLISHED, result.exitCode(), result.message());
        final SynxChunkRecord record = record(result.directory(), 0, 0);

        final List<ChunkData.Section> sections = sections(record);
        assertEquals(Block.AIR.stateId(), sections.get(0).blockStates().get(5, -59 & 15, 5), "marker above the lever");
        assertEquals(Block.AIR.stateId(), sections.get(0).blockStates().get(4, -59 & 15, 1), "marker above the chest");
        assertEquals("minecraft:lever", Block.fromStateId(sections.get(0).blockStates().get(5, -60 & 15, 5)).name());

        final SynxBlockEntity door = blockEntity(record, 5, -60, 5);
        assertEquals("synaxis:door", door.handler());
        assertEquals(1, door.nbt().getByte("locked"));
        final SynxBlockEntity loot = blockEntity(record, 4, -60, 1);
        assertEquals("synaxis:loot", loot.handler());
        assertEquals("t1", loot.nbt().getString("table"));
        assertEquals("minecraft:chest", loot.nbt().getString("id"), "merged over the chest's own block entity");

        final Map<Integer, Block> clientBlockEntities = packet(record).chunkData().blockEntities();
        final Block chest = clientBlockEntities.values().stream().filter(block -> block.name().equals("minecraft:chest"))
                .findFirst().orElseThrow(() -> new AssertionError(clientBlockEntities.toString()));
        assertEquals(CompoundBinaryTag.builder().putString("table", "t1").build(), chest.nbt(),
                "the client gets the handler's tags and nothing else");
        assertFalse(clientBlockEntities.values().stream().anyMatch(block -> block.name().equals("minecraft:lever")),
                "a lever has no block entity type, the client gets nothing for it");
    }

    /** The seven block entity types without a block of the same name all reach segment ③. */
    @Test
    public void everyBlockEntityOfTheSourceIsInSegmentThree() throws Exception {
        final Baker.Result result = bake(RegionFixtures.world(), CHUNK_0_0);
        final SynxChunkRecord record = record(result.directory(), 0, 0);

        final List<String> ids = new ArrayList<>();
        for (SynxBlockEntity blockEntity : record.blockEntities()) ids.add(blockEntity.nbt().getString("id"));
        assertTrue(ids.containsAll(List.of("minecraft:sign", "minecraft:banner", "minecraft:skull",
                "minecraft:hanging_sign", "minecraft:mob_spawner", "minecraft:shelf", "minecraft:brushable_block")), ids.toString());
        final String text = blockEntity(record, 1, -60, 1).nbt().getCompound("front_text").getList("messages").toString();
        assertTrue(text.contains("Hello") && text.contains("Synaxis"), text);
    }

    /** §19: the static entities of {@code entities/} go into segment ③ whole. */
    @Test
    public void theSourceEntitiesAreInSegmentThree() throws Exception {
        final Baker.Result result = bake(RegionFixtures.world(), CHUNK_0_0);
        final SynxChunkRecord record = record(result.directory(), 0, 0);

        final List<CompoundBinaryTag> expected;
        try (AnvilWorld world = AnvilWorld.open(RegionFixtures.world(), OVERWORLD, REGISTRIES.biome(), HANDLERS::containsKey)) {
            expected = world.readChunk(0, 0).entities();
        }
        assertEquals(2, expected.size());
        assertEquals(expected, record.staticEntities());
        assertEquals(Set.of("minecraft:item_frame", "minecraft:text_display"),
                Set.copyOf(record.staticEntities().stream().map(entity -> entity.getString("id")).toList()));
    }

    private static SynxBlockEntity blockEntity(SynxChunkRecord record, int x, int y, int z) {
        return record.blockEntities().stream()
                .filter(blockEntity -> blockEntity.sectionY() == y >> 4 && blockEntity.index() == SynxBlockEntity.index(x, y, z))
                .findFirst().orElseThrow(() -> new AssertionError("no block entity at " + x + ", " + y + ", " + z));
    }
}
