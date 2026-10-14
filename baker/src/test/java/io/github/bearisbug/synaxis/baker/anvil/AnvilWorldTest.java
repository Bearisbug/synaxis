package io.github.bearisbug.synaxis.baker.anvil;

import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.registry.DynamicRegistry;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.world.biome.Biome;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.DoubleBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The offline Anvil reader against a save written by the vanilla 26.2 server (REQ-M3-020, REQ-M3-022).
 * The fixture and what it holds are described in {@code tools/anvil-fixture/gen.sh}; the variants the
 * vanilla server cannot produce (an older DataVersion, the lower case {@code status} key) are made by
 * editing one chunk of a copy.
 */
public class AnvilWorldTest {
    private static final Key OVERWORLD = Key.key("minecraft:overworld");
    private static final DynamicRegistry<Biome> BIOMES = Registries.vanilla().biome();
    private static final Predicate<String> HANDLERS = Set.of("synaxis:door", "synaxis:loot")::contains;

    @TempDir
    Path temp;

    private static AnvilWorld open(Path world) throws IOException {
        return AnvilWorld.open(world, OVERWORLD, BIOMES, HANDLERS);
    }

    private static Block blockAt(AnvilChunk chunk, int x, int y, int z) {
        for (AnvilSection section : chunk.sections()) {
            if (section.sectionY() == y >> 4) {
                return Block.fromStateId(section.blocks().get(x & 15, y & 15, z & 15));
            }
        }
        throw new AssertionError("no section for y " + y);
    }

    private static AnvilBlockEntity blockEntityAt(AnvilChunk chunk, int x, int y, int z) {
        for (AnvilBlockEntity blockEntity : chunk.blockEntities()) {
            if (blockEntity.sectionY() == y >> 4 && blockEntity.index() == AnvilBlockEntity.index(x, y, z)) {
                return blockEntity;
            }
        }
        throw new AssertionError("no block entity at " + x + ", " + y + ", " + z + " in " + chunk.blockEntities());
    }

    @Test
    public void listsTheChunksOfTheDimension() throws IOException {
        try (AnvilWorld world = open(RegionFixtures.world())) {
            assertEquals(List.of(new AnvilWorld.ChunkPos(0, 0), new AnvilWorld.ChunkPos(3, 0)), world.chunks());
        }
    }

    @Test
    public void readsTheBlocksBiomesAndLightOfAFullChunk() throws IOException {
        try (AnvilWorld world = open(RegionFixtures.world())) {
            AnvilChunk chunk = world.readChunk(0, 0);
            assertNotNull(chunk);
            assertEquals(0, chunk.chunkX());
            assertEquals(0, chunk.chunkZ());
            assertTrue(chunk.lightOn());
            assertEquals(24, chunk.sections().size(), "sections -4..19 carry block states, the light only ones do not");

            assertEquals(Block.BEDROCK, blockAt(chunk, 0, -64, 0));
            assertEquals(Block.GRASS_BLOCK, blockAt(chunk, 0, -61, 0));
            assertEquals(Block.AIR, blockAt(chunk, 0, -60, 0));
            assertEquals("minecraft:oak_sign", blockAt(chunk, 1, -60, 1).name());
            assertEquals("minecraft:chest", blockAt(chunk, 4, -60, 1).name());

            AnvilSection surface = chunk.sections().get(0);
            assertEquals(-4, surface.sectionY());
            assertEquals(BIOMES.getId(Biome.PLAINS), surface.biomes().get(0, 0, 0));
            assertNotNull(surface.skyLight());
            assertEquals(2048, surface.skyLight().length);
        }
    }

    @Test
    public void readsAChunkThatIsNotThereAsNull() throws IOException {
        try (AnvilWorld world = open(RegionFixtures.world())) {
            assertNull(world.readChunk(1, 0));
            assertNull(world.readChunk(40, 40), "no region file at all");
        }
    }

    /**
     * The seven 26.2 block entity types without a block of the same name (DESIGN-M3 §6): a reader that
     * resolved block entities through {@code Block.fromKey} would drop all of them.
     */
    @Test
    public void keepsEveryBlockEntityWholeIncludingTheSignText() throws IOException {
        try (AnvilWorld world = open(RegionFixtures.world())) {
            AnvilChunk chunk = world.readChunk(0, 0);
            assertNotNull(chunk);

            AnvilBlockEntity sign = blockEntityAt(chunk, 1, -60, 1);
            assertEquals(-4, sign.sectionY());
            assertEquals((4 * 16 + 1) * 16 + 1, sign.index());
            assertEquals(1, sign.localX());
            assertEquals(-60, sign.y());
            assertEquals(1, sign.localZ());
            assertNull(sign.handler());
            assertEquals("minecraft:sign", sign.nbt().getString("id"));
            ListBinaryTag messages = sign.nbt().getCompound("front_text").getList("messages");
            assertEquals(4, messages.size());
            String text = messages.toString();
            assertTrue(text.contains("Hello") && text.contains("Synaxis"), text);

            assertEquals("minecraft:banner", blockEntityAt(chunk, 2, -60, 1).nbt().getString("id"));
            assertEquals("minecraft:skull", blockEntityAt(chunk, 3, -60, 1).nbt().getString("id"));
            assertEquals("minecraft:hanging_sign", blockEntityAt(chunk, 6, -60, 1).nbt().getString("id"));
            assertEquals("minecraft:mob_spawner", blockEntityAt(chunk, 7, -60, 1).nbt().getString("id"));
            assertEquals("minecraft:shelf", blockEntityAt(chunk, 8, -60, 1).nbt().getString("id"));
            assertEquals("minecraft:brushable_block", blockEntityAt(chunk, 10, -60, 1).nbt().getString("id"));
            assertEquals(3, blockEntityAt(chunk, 3, -60, 1).nbt().getInt("x"), "the source coordinates stay in");
        }
    }

    @Test
    public void readsTheStaticEntitiesOfTheChunk() throws IOException {
        try (AnvilWorld world = open(RegionFixtures.world())) {
            AnvilChunk chunk = world.readChunk(0, 0);
            assertNotNull(chunk);
            assertEquals(2, chunk.entities().size(), chunk.entities().toString());

            CompoundBinaryTag frame = entity(chunk, "minecraft:item_frame");
            ListBinaryTag framePos = frame.getList("Pos", BinaryTagTypes.DOUBLE);
            assertEquals(7.5, framePos.getDouble(0));
            assertEquals(7.5, framePos.getDouble(2));
            assertEquals(1, frame.getByte("Facing"));

            CompoundBinaryTag display = entity(chunk, "minecraft:text_display");
            ListBinaryTag displayPos = display.getList("Pos", BinaryTagTypes.DOUBLE);
            assertEquals(8.5, displayPos.getDouble(0));
            assertEquals(-59.0, displayPos.getDouble(1));
            assertNotNull(display.get("text"), "the entity keeps its own tags: " + display);
        }
    }

    private static CompoundBinaryTag entity(AnvilChunk chunk, String id) {
        return chunk.entities().stream()
                .filter(entity -> entity.getString("id").equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + id + " in " + chunk.entities()));
    }

    @Test
    public void rejectsAChunkVanillaDidNotGenerateToFull() throws IOException {
        try (AnvilWorld world = open(RegionFixtures.world())) {
            AnvilSourceException error = assertThrows(AnvilSourceException.class, () -> world.readChunk(3, 0));
            assertEquals(3, error.chunkX());
            assertEquals(0, error.chunkZ());
            assertTrue(error.getMessage().contains("chunk 3, 0"), error.getMessage());
            assertTrue(error.getMessage().contains("minecraft:initialize_light"), error.getMessage());
        }
    }

    @Test
    public void acceptsTheLowerCaseStatusKey() throws IOException {
        Path world = RegionFixtures.copyWorld(temp);
        RegionFixtures.rewriteChunk(world.resolve(RegionFixtures.REGION), 0, 0, chunk ->
                chunk.remove("Status").putString("status", "minecraft:full"));
        try (AnvilWorld anvil = open(world)) {
            AnvilChunk chunk = anvil.readChunk(0, 0);
            assertNotNull(chunk);
            assertEquals("minecraft:oak_sign", blockAt(chunk, 1, -60, 1).name());
        }
    }

    @Test
    public void rejectsALowerCaseStatusThatIsNotFull() throws IOException {
        Path world = RegionFixtures.copyWorld(temp);
        RegionFixtures.rewriteChunk(world.resolve(RegionFixtures.REGION), 0, 0, chunk ->
                chunk.remove("Status").putString("status", "minecraft:carvers"));
        try (AnvilWorld anvil = open(world)) {
            AnvilSourceException error = assertThrows(AnvilSourceException.class, () -> anvil.readChunk(0, 0));
            assertTrue(error.getMessage().contains("minecraft:carvers"), error.getMessage());
        }
    }

    @Test
    public void rejectsAChunkWithoutAnyStatus() throws IOException {
        Path world = RegionFixtures.copyWorld(temp);
        RegionFixtures.rewriteChunk(world.resolve(RegionFixtures.REGION), 0, 0, chunk -> chunk.remove("Status"));
        try (AnvilWorld anvil = open(world)) {
            assertThrows(AnvilSourceException.class, () -> anvil.readChunk(0, 0));
        }
    }

    @Test
    public void rejectsAnotherDataVersionAndNamesTheUpgrade() throws IOException {
        Path world = RegionFixtures.copyWorld(temp);
        RegionFixtures.rewriteChunk(world.resolve(RegionFixtures.REGION), 0, 0, chunk ->
                chunk.putInt("DataVersion", 4790));
        try (AnvilWorld anvil = open(world)) {
            AnvilSourceException error = assertThrows(AnvilSourceException.class, () -> anvil.readChunk(0, 0));
            assertTrue(error.getMessage().contains("chunk 0, 0"), error.getMessage());
            assertTrue(error.getMessage().contains("4790"), error.getMessage());
            assertTrue(error.getMessage().contains("--forceUpgrade"), error.getMessage());
        }
    }

    /**
     * Vanilla only upgrades, so a chunk a later Minecraft saved cannot go back to 26.2: the fix is a backup.
     */
    @Test
    public void rejectsALaterDataVersionAndNamesTheBackup() throws IOException {
        Path world = RegionFixtures.copyWorld(temp);
        RegionFixtures.rewriteChunk(world.resolve(RegionFixtures.REGION), 0, 0, chunk ->
                chunk.putInt("DataVersion", 5000));
        try (AnvilWorld anvil = open(world)) {
            AnvilSourceException error = assertThrows(AnvilSourceException.class, () -> anvil.readChunk(0, 0));
            assertTrue(error.getMessage().contains("5000") && error.getMessage().contains("last opened with 26.2"),
                    error.getMessage());
            assertFalse(error.getMessage().contains("--forceUpgrade"), error.getMessage());
        }
    }

    @Test
    public void rejectsEntitiesOfAnotherDataVersion() throws IOException {
        Path world = RegionFixtures.copyWorld(temp);
        RegionFixtures.rewriteChunk(world.resolve(RegionFixtures.ENTITIES), 0, 0, chunk ->
                chunk.putInt("DataVersion", 4790));
        try (AnvilWorld anvil = open(world)) {
            AnvilSourceException error = assertThrows(AnvilSourceException.class, () -> anvil.readChunk(0, 0));
            assertTrue(error.getMessage().contains("entities"), error.getMessage());
        }
    }

    /**
     * The window check reads the y of {@code Pos} and a shard spawns the entity at {@code Pos} turned by
     * {@code Rotation}, so a static entity without them is refused with its chunk instead of failing later.
     */
    @Test
    public void rejectsAStaticEntityWithoutThreeFinitePosOrTwoRotation() throws IOException {
        List<Map.Entry<String, UnaryOperator<CompoundBinaryTag>>> edits = List.of(
                Map.entry("no-pos", entity -> entity.remove("Pos")),
                Map.entry("two-pos", entity -> entity.put("Pos", ListBinaryTag.from(List.of(
                        DoubleBinaryTag.doubleBinaryTag(8.5), DoubleBinaryTag.doubleBinaryTag(-59.0))))),
                Map.entry("nan-pos", entity -> entity.put("Pos", ListBinaryTag.from(List.of(DoubleBinaryTag.doubleBinaryTag(8.5),
                        DoubleBinaryTag.doubleBinaryTag(Double.NaN), DoubleBinaryTag.doubleBinaryTag(8.5))))),
                Map.entry("no-rotation", entity -> entity.remove("Rotation")));
        for (Map.Entry<String, UnaryOperator<CompoundBinaryTag>> edit : edits) {
            Path world = RegionFixtures.copyWorld(Files.createDirectory(temp.resolve(edit.getKey())));
            RegionFixtures.rewriteChunk(world.resolve(RegionFixtures.ENTITIES), 0, 0, chunk ->
                    chunk.put("Entities", ListBinaryTag.from(chunk.getList("Entities", BinaryTagTypes.COMPOUND).stream()
                            .map(CompoundBinaryTag.class::cast)
                            .map(entity -> entity.getString("id").equals("minecraft:text_display")
                                    ? edit.getValue().apply(entity) : entity)
                            .toList())));
            try (AnvilWorld anvil = open(world)) {
                AnvilSourceException error = assertThrows(AnvilSourceException.class, () -> anvil.readChunk(0, 0),
                        edit.getKey());
                assertTrue(error.getMessage().contains("chunk 0, 0")
                        && error.getMessage().contains("minecraft:text_display"), error.getMessage());
            }
        }
    }

    @Test
    public void mapsDataMarkersOntoTheBlockBelow() throws IOException {
        try (AnvilWorld world = open(RegionFixtures.world())) {
            AnvilChunk chunk = world.readChunk(0, 0);
            assertNotNull(chunk);

            // The structure blocks themselves are gone, block and block entity
            assertEquals(Block.AIR, blockAt(chunk, 5, -59, 5));
            assertEquals(Block.AIR, blockAt(chunk, 4, -59, 1));
            assertEquals(Block.AIR, blockAt(chunk, 9, -60, 9));
            assertFalse(chunk.blockEntities().stream()
                            .anyMatch(blockEntity -> blockEntity.nbt().getString("id").equals("minecraft:structure_block")),
                    chunk.blockEntities().toString());

            // A block without a block entity of its own gets one carrying the handler and the SNBT
            assertEquals("minecraft:lever", blockAt(chunk, 5, -60, 5).name());
            AnvilBlockEntity door = blockEntityAt(chunk, 5, -60, 5);
            assertEquals("synaxis:door", door.handler());
            assertEquals(1, door.nbt().getByte("locked"));

            // A vanilla block entity keeps its tags, the marker's SNBT is merged over them
            AnvilBlockEntity chest = blockEntityAt(chunk, 4, -60, 1);
            assertEquals("synaxis:loot", chest.handler());
            assertEquals("minecraft:chest", chest.nbt().getString("id"));
            assertEquals("t1", chest.nbt().getString("table"));

            assertEquals(List.of(new HiddenArea(0, 0, 15, 15)), chunk.hiddenAreas());
        }
    }

    @Test
    public void rejectsAMarkerWhoseHandlerIsNotRegistered() throws IOException {
        try (AnvilWorld world = AnvilWorld.open(RegionFixtures.world(), OVERWORLD, BIOMES, "synaxis:loot"::equals)) {
            AnvilSourceException error = assertThrows(AnvilSourceException.class, () -> world.readChunk(0, 0));
            assertTrue(error.getMessage().contains("synaxis:door"), error.getMessage());
            assertTrue(error.getMessage().contains("5, -59, 5"), error.getMessage());
        }
    }

    @Test
    public void rejectsAMarkerWithMalformedSnbt() throws IOException {
        Path world = RegionFixtures.copyWorld(temp);
        RegionFixtures.rewriteChunk(world.resolve(RegionFixtures.REGION), 0, 0, chunk -> {
            ListBinaryTag.Builder<CompoundBinaryTag> blockEntities = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
            for (CompoundBinaryTag blockEntity : chunk.getList("block_entities").stream()
                    .map(CompoundBinaryTag.class::cast).toList()) {
                blockEntities.add(blockEntity.getString("metadata").startsWith("synaxis:door")
                        ? blockEntity.putString("metadata", "synaxis:door {locked:")
                        : blockEntity);
            }
            return chunk.put("block_entities", blockEntities.build());
        });
        try (AnvilWorld anvil = open(world)) {
            AnvilSourceException error = assertThrows(AnvilSourceException.class, () -> anvil.readChunk(0, 0));
            assertTrue(error.getMessage().contains("5, -59, 5"), error.getMessage());
        }
    }

    /**
     * {@code id} and the coordinates say what and where the record is; a marker must not rewrite them.
     */
    @Test
    public void rejectsAMarkerWhoseSnbtSetsTheIdOrTheCoordinates() throws IOException {
        for (String tag : List.of("id:\"minecraft:chest\"", "x:3", "y:3", "z:3")) {
            Path world = RegionFixtures.copyWorld(Files.createDirectory(temp.resolve(tag.substring(0, 1))));
            RegionFixtures.rewriteChunk(world.resolve(RegionFixtures.REGION), 0, 0, chunk ->
                    editBlockEntities(chunk, blockEntities -> blockEntities.stream()
                            .map(blockEntity -> blockEntity.getString("metadata").startsWith("synaxis:door")
                                    ? blockEntity.putString("metadata", "synaxis:door {" + tag + "}")
                                    : blockEntity)
                            .toList()));
            try (AnvilWorld anvil = open(world)) {
                AnvilSourceException error = assertThrows(AnvilSourceException.class, () -> anvil.readChunk(0, 0), tag);
                assertTrue(error.getMessage().contains("5, -59, 5"), error.getMessage());
            }
        }
    }

    /**
     * The lower of two stacked markers would turn into air first and the upper one would then mark that air.
     */
    @Test
    public void rejectsAMarkerOnTopOfAnotherMarker() throws IOException {
        Path world = RegionFixtures.copyWorld(temp);
        RegionFixtures.rewriteChunk(world.resolve(RegionFixtures.REGION), 0, 0, chunk ->
                editBlockEntities(chunk, blockEntities -> {
                    List<CompoundBinaryTag> edited = new ArrayList<>(blockEntities);
                    edited.add(CompoundBinaryTag.builder()
                            .putString("id", "minecraft:structure_block")
                            .putInt("x", 5).putInt("y", -60).putInt("z", 5)
                            .putString("mode", "DATA")
                            .putString("metadata", "synaxis:loot {}")
                            .build());
                    return edited;
                }));
        try (AnvilWorld anvil = open(world)) {
            AnvilSourceException error = assertThrows(AnvilSourceException.class, () -> anvil.readChunk(0, 0));
            assertTrue(error.getMessage().contains("5, -59, 5"), error.getMessage());
        }
    }

    @Test
    public void rejectsARegionEntryThatHoldsAnotherChunk() throws IOException {
        Path world = RegionFixtures.copyWorld(temp);
        RegionFixtures.rewriteChunk(world.resolve(RegionFixtures.REGION), 0, 0, chunk -> chunk.putInt("xPos", 5));
        try (AnvilWorld anvil = open(world)) {
            AnvilSourceException error = assertThrows(AnvilSourceException.class, () -> anvil.readChunk(0, 0));
            assertTrue(error.getMessage().contains("holds chunk 5, 0"), error.getMessage());
        }
    }

    @Test
    public void rejectsAnEntitiesEntryThatHoldsAnotherChunk() throws IOException {
        Path world = RegionFixtures.copyWorld(temp);
        RegionFixtures.rewriteChunk(world.resolve(RegionFixtures.ENTITIES), 0, 0, chunk ->
                chunk.putIntArray("Position", new int[]{5, 0}));
        try (AnvilWorld anvil = open(world)) {
            AnvilSourceException error = assertThrows(AnvilSourceException.class, () -> anvil.readChunk(0, 0));
            assertTrue(error.getMessage().contains("entities") && error.getMessage().contains("5, 0"),
                    error.getMessage());
        }
    }

    @Test
    public void rejectsABlockNameThatIsNotAKey() throws IOException {
        Path world = RegionFixtures.copyWorld(temp);
        RegionFixtures.rewriteChunk(world.resolve(RegionFixtures.REGION), 0, 0, chunk ->
                editSurfaceBlockStates(chunk, blockStates -> {
                    ListBinaryTag palette = blockStates.getList("palette", BinaryTagTypes.COMPOUND);
                    return blockStates.put("palette", palette.set(0, palette.getCompound(0).putString("Name", "Not A Key"), null));
                }));
        try (AnvilWorld anvil = open(world)) {
            AnvilSourceException error = assertThrows(AnvilSourceException.class, () -> anvil.readChunk(0, 0));
            assertTrue(error.getMessage().contains("Not A Key"), error.getMessage());
        }
    }

    @Test
    public void rejectsPackedBlockStatesOfTheWrongLength() throws IOException {
        Path world = RegionFixtures.copyWorld(temp);
        RegionFixtures.rewriteChunk(world.resolve(RegionFixtures.REGION), 0, 0, chunk ->
                editSurfaceBlockStates(chunk, blockStates -> {
                    long[] packed = blockStates.getLongArray("data");
                    return blockStates.putLongArray("data", Arrays.copyOf(packed, packed.length / 2));
                }));
        try (AnvilWorld anvil = open(world)) {
            AnvilSourceException error = assertThrows(AnvilSourceException.class, () -> anvil.readChunk(0, 0));
            assertTrue(error.getMessage().contains("section -4"), error.getMessage());
        }
    }

    @Test
    public void rejectsAPackedIndexBeyondThePalette() throws IOException {
        Path world = RegionFixtures.copyWorld(temp);
        RegionFixtures.rewriteChunk(world.resolve(RegionFixtures.REGION), 0, 0, chunk ->
                editSurfaceBlockStates(chunk, blockStates -> {
                    int paletteSize = blockStates.getList("palette").size();
                    assertTrue(paletteSize > 1 && paletteSize < 16, "four bits per entry, index 15 unused: " + paletteSize);
                    long[] packed = blockStates.getLongArray("data");
                    packed[0] = -1L;
                    return blockStates.putLongArray("data", packed);
                }));
        try (AnvilWorld anvil = open(world)) {
            AnvilSourceException error = assertThrows(AnvilSourceException.class, () -> anvil.readChunk(0, 0));
            assertTrue(error.getMessage().contains("section -4"), error.getMessage());
        }
    }

    /**
     * A damaged entry, as a copy taken while vanilla was writing leaves: a broken zlib stream, an uncompressed tag cut
     * short (an {@code EOFException} without a message) and an unknown tag id (adventure's
     * {@code IllegalArgumentException}) all name the chunk and the region file.
     */
    @Test
    public void rejectsAnEntryThatDoesNotDecodeAndNamesItsChunkAndFile() throws IOException {
        List<Map.Entry<String, byte[]>> entries = List.of(
                Map.entry("zlib", new byte[]{2, 1, 2, 3, 4}),
                Map.entry("cut", new byte[]{3, 10, 0, 0, 1, 0, 1, 'b'}),
                Map.entry("tag", new byte[]{3, 10, 0, 0, 99}));
        for (Map.Entry<String, byte[]> entry : entries) {
            Path world = RegionFixtures.copyWorld(Files.createDirectory(temp.resolve(entry.getKey())));
            writeEntry(world.resolve(RegionFixtures.REGION), entry.getValue());
            try (AnvilWorld anvil = open(world)) {
                IOException error = assertThrows(IOException.class, () -> anvil.readChunk(0, 0), entry.getKey());
                assertTrue(String.valueOf(error.getMessage()).contains("chunk 0, 0 of ")
                        && error.getMessage().contains("r.0.0.mca does not decode"), entry.getKey() + ": " + error);
            }
        }
    }

    /**
     * Twelve bytes declare a long array of 128 MiB: the reader refuses the length before it allocates the array.
     */
    @Test
    public void refusesAnEntryWhoseNbtGoesPastTheReadLimit() throws IOException {
        Path world = RegionFixtures.copyWorld(temp);
        writeEntry(world.resolve(RegionFixtures.REGION), new byte[]{3, 10, 0, 0, 12, 0, 1, 'd', 1, 0, 0, 0});
        try (AnvilWorld anvil = open(world)) {
            IOException error = assertThrows(IOException.class, () -> anvil.readChunk(0, 0));
            assertTrue(String.valueOf(error.getMessage()).contains("chunk 0, 0 of ")
                    && error.getMessage().contains("67108864"), String.valueOf(error));
        }
    }

    /**
     * An oversized chunk vanilla moved to {@code c.<x>.<z>.mcc} and an LZ4 chunk each name what to do about them.
     */
    @Test
    public void namesTheFixForAnOversizedOrLz4Chunk() throws IOException {
        Path oversized = RegionFixtures.copyWorld(Files.createDirectory(temp.resolve("mcc")));
        writeEntry(oversized.resolve(RegionFixtures.REGION), new byte[]{(byte) 0x82});
        try (AnvilWorld anvil = open(oversized)) {
            IOException error = assertThrows(IOException.class, () -> anvil.readChunk(0, 0));
            assertTrue(error.getMessage().contains("c.0.0.mcc") && error.getMessage().contains("over 1 MiB"),
                    error.getMessage());
        }
        Path lz4 = RegionFixtures.copyWorld(Files.createDirectory(temp.resolve("lz4")));
        writeEntry(lz4.resolve(RegionFixtures.REGION), new byte[]{4, 0});
        try (AnvilWorld anvil = open(lz4)) {
            IOException error = assertThrows(IOException.class, () -> anvil.readChunk(0, 0));
            assertTrue(error.getMessage().contains("LZ4") && error.getMessage().contains("--recreateRegionFiles"),
                    error.getMessage());
        }
    }

    /**
     * Points chunk 0, 0 of {@code regionFile} at an entry appended to it: the compression byte and the data in
     * {@code entry}, after the length vanilla writes.
     */
    private static void writeEntry(Path regionFile, byte[] entry) throws IOException {
        final int sector = 4096;
        byte[] file = Files.readAllBytes(regionFile);
        int firstSector = (file.length + sector - 1) / sector;
        int sectors = (4 + entry.length + sector - 1) / sector;
        ByteBuffer out = ByteBuffer.allocate((firstSector + sectors) * sector);
        out.put(file);
        out.putInt(0, (firstSector << 8) | sectors);
        out.position(firstSector * sector);
        out.putInt(entry.length).put(entry);
        Files.write(regionFile, out.array());
    }

    private static CompoundBinaryTag editBlockEntities(CompoundBinaryTag chunk,
                                                       UnaryOperator<List<CompoundBinaryTag>> edit) {
        List<CompoundBinaryTag> blockEntities = chunk.getList("block_entities", BinaryTagTypes.COMPOUND).stream()
                .map(CompoundBinaryTag.class::cast).toList();
        return chunk.put("block_entities", ListBinaryTag.from(edit.apply(blockEntities)));
    }

    /**
     * Edits {@code block_states} of section -4, the one holding the fixture's surface and content.
     */
    private static CompoundBinaryTag editSurfaceBlockStates(CompoundBinaryTag chunk, UnaryOperator<CompoundBinaryTag> edit) {
        ListBinaryTag.Builder<CompoundBinaryTag> sections = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        for (CompoundBinaryTag section : chunk.getList("sections", BinaryTagTypes.COMPOUND).stream()
                .map(CompoundBinaryTag.class::cast).toList()) {
            sections.add(section.getByte("Y") == -4
                    ? section.put("block_states", edit.apply(section.getCompound("block_states")))
                    : section);
        }
        return chunk.put("sections", sections.build());
    }

    @Test
    public void readsWithoutWritingAnything()throws IOException, NoSuchAlgorithmException {
        Path world = RegionFixtures.copyWorld(temp);
        Path region = world.resolve(RegionFixtures.REGION);
        Path entities = world.resolve(RegionFixtures.ENTITIES);
        byte[] regionDigest = sha256(region);
        byte[] entitiesDigest = sha256(entities);
        assertTrue(region.toFile().setWritable(false));
        assertTrue(entities.toFile().setWritable(false));

        try (AnvilWorld anvil = open(world)) {
            assertNotNull(anvil.readChunk(0, 0));
            assertNull(anvil.readChunk(-40, -40));
        }

        assertArrayEquals(regionDigest, sha256(region));
        assertArrayEquals(entitiesDigest, sha256(entities));
        assertFalse(Files.exists(world.resolve("dimensions/minecraft/overworld/region/r.-2.-2.mca")),
                "reading a missing region must not create it");
    }

    /**
     * Only the 26.1+ layout is read: a pre-26.1 save keeps its overworld in {@code <world>/region}.
     */
    @Test
    public void refusesASaveWithoutTheDimensionsLayout() throws IOException {
        Path legacy = temp.resolve("legacy");
        Files.createDirectories(legacy.resolve("region"));
        Files.copy(RegionFixtures.world().resolve(RegionFixtures.REGION), legacy.resolve("region/r.0.0.mca"));
        IOException error = assertThrows(IOException.class, () -> open(legacy));
        assertTrue(error.getMessage().contains("dimensions"), error.getMessage());
    }

    /**
     * Only a save in the pre-26.1 layout is told to upgrade: a directory one level off and a save without the
     * dimension each name their own fix.
     */
    @Test
    public void refusesADirectoryThatIsNotASaveOrLacksTheDimension() throws IOException {
        Path notASave = Files.createDirectory(temp.resolve("saves"));
        IOException error = assertThrows(IOException.class, () -> open(notASave));
        assertTrue(error.getMessage().contains("is not a save, give the directory that holds level.dat"),
                error.getMessage());

        Path noDimension = Files.createDirectory(temp.resolve("map"));
        Files.write(noDimension.resolve("level.dat"), new byte[0]);
        error = assertThrows(IOException.class, () -> open(noDimension));
        assertTrue(error.getMessage().contains("has not generated minecraft:overworld"), error.getMessage());
        assertFalse(error.getMessage().contains("upgraded"), error.getMessage());
    }

    private static byte[] sha256(Path file) throws IOException, NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
    }
}
