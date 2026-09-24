package io.github.bearisbug.synaxis.baker.anvil;

import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.registry.DynamicRegistry;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.world.biome.Biome;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

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
     * {@code sign}, {@code banner} and {@code skull} have no block of the same name, so a reader that
     * resolved block entities through {@code Block.fromKey} would drop all three.
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

    @Test
    public void readsWithoutWritingAnything() throws IOException, NoSuchAlgorithmException {
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

    private static byte[] sha256(Path file) throws IOException, NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
    }
}
