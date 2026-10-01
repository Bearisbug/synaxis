package io.github.bearisbug.synaxis.baker.anvil;

import io.github.bearisbug.synaxis.baker.AnvilSource;
import io.github.bearisbug.synaxis.baker.BakeTesting;
import io.github.bearisbug.synaxis.baker.Baker;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack.Layer;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack.Sample;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.network.packet.server.play.data.ChunkData;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import io.github.bearisbug.synaxis.tag.Tag;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static io.github.bearisbug.synaxis.baker.BakeTesting.REGISTRIES;
import static io.github.bearisbug.synaxis.baker.BakeTesting.record;
import static io.github.bearisbug.synaxis.baker.BakeTesting.request;
import static io.github.bearisbug.synaxis.baker.BakeTesting.sections;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hidden areas in the far view pack of an Anvil source ({@code docs/DESIGN-M3.md} {@code API-M3-014} 隐藏区域,
 * {@code API-M3-003} 数据标记; §19 「隐藏区域在高度场包里被替换」). The frozen fixture's only full chunk is 0, 0,
 * and its DATA marker at 9, -60, 9 says {@code synaxis:hidden 0 0 15 15}: the whole chunk.
 */
public class AnvilFarViewTest {
    private static final Key OVERWORLD = Key.key("minecraft:overworld");
    private static final SynxManifest.Bounds CHUNK_0_0 = new SynxManifest.Bounds(0, 0, 0, 0);
    private static final Map<String, BlockHandler> HANDLERS = Map.of(
            "synaxis:door", new BakeTesting.TestHandler(Key.key("synaxis:door"), List.of()),
            "synaxis:loot", new BakeTesting.TestHandler(Key.key("synaxis:loot"), List.of(Tag.String("table"))));

    @TempDir
    Path temp;

    private record Baked(Path build, List<HiddenArea> hidden, FarViewPack.Reader pack, List<Sample> fine, List<Sample> coarse) {
        Sample fine(int x, int z) {
            return fine.get(z * FarViewPack.BLOCK_SIDE + x);
        }

        /** The lowest top of the ring of fine samples 0..3 × 0..3 of chunk 0, 0; outside the chunk an empty column, 0. */
        int ring(int minX, int minZ, int maxX, int maxZ) {
            int lowest = Integer.MAX_VALUE;
            for (int x = minX - 1; x <= maxX + 1; x++) {
                for (int z = minZ - 1; z <= maxZ + 1; z++) {
                    if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) continue;
                    lowest = Math.min(lowest, x < 0 || z < 0 || x > 3 || z > 3 ? 0 : Math.max(0, fine(x, z).top()));
                }
            }
            return lowest;
        }

        Sample hidden(int height) {
            return new Sample(Block.STONE.stateId(), 0, height, 0, 0, pack.biomes().indexOf("minecraft:plains"));
        }
    }

    private Baked bake(Path world, String name) throws Exception {
        try (AnvilSource source = AnvilSource.open(world, OVERWORLD, CHUNK_0_0, AnvilSource.DEFAULT_MIN_Y, REGISTRIES,
                HANDLERS::containsKey)) {
            final Baker.Result result = Baker.bake(request("anvil", source, temp.resolve(name), 2, HANDLERS::get));
            assertEquals(Baker.EXIT_PUBLISHED, result.exitCode(), result.message());
            final FarViewPack.Reader pack = FarViewPack.Reader.read(Files.readAllBytes(result.directory().resolve("farview")
                    .resolve("t.0.0.hfp")));
            return new Baked(result.directory(), source.hiddenAreas(), pack, pack.samples(pack.blocks(Layer.FINE).getFirst()),
                    pack.samples(pack.blocks(Layer.COARSE).getFirst()));
        }
    }

    /** A copy of the fixture whose hidden marker says {@code metadata}. */
    private Path withHiddenMarker(String name, String metadata) throws Exception {
        final Path world = RegionFixtures.copyWorld(temp.resolve(name + "-save"));
        RegionFixtures.rewriteChunk(world.resolve(RegionFixtures.REGION), 0, 0, chunk -> {
            final ListBinaryTag.Builder<CompoundBinaryTag> blockEntities = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
            for (CompoundBinaryTag blockEntity : chunk.getList("block_entities").stream().map(CompoundBinaryTag.class::cast).toList()) {
                final boolean marker = blockEntity.getInt("x") == 9 && blockEntity.getInt("y") == -60 && blockEntity.getInt("z") == 9;
                blockEntities.add(marker ? blockEntity.putString("metadata", metadata) : blockEntity);
            }
            return chunk.put("block_entities", blockEntities.build());
        });
        return world;
    }

    /**
     * §19: every fine sample of chunk 0, 0 meets blocks 0..15 × 0..15 and becomes stone of plains without fluid at
     * the lowest top of its ring, which lies outside the bounds and so is 0; the chunk's coarse sample likewise.
     * The region file keeps the chunk's blocks, only the marker is air.
     */
    @Test
    public void theFixturesHiddenMarkerReplacesItsChunkOnBothLayers() throws Exception {
        final Baked baked = bake(RegionFixtures.world(), "fixture");
        assertEquals(List.of(new HiddenArea(0, 0, 15, 15)), baked.hidden());
        assertEquals(-64, baked.pack().header().minY());

        final int ring = baked.ring(0, 0, 3, 3);
        assertEquals(0, ring);
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) assertEquals(baked.hidden(ring), baked.fine(x, z), x + "," + z);
        }
        assertEquals(baked.hidden(0), baked.coarse().getFirst(), "every coarse sample around chunk 0, 0 is outside the bounds");

        final List<ChunkData.Section> sections = sections(record(baked.build(), 0, 0));
        try (AnvilWorld world = AnvilWorld.open(RegionFixtures.world(), OVERWORLD, REGISTRIES.biome(), HANDLERS::containsKey)) {
            final AnvilChunk source = world.readChunk(0, 0);
            for (AnvilSection section : source.sections()) {
                final int index = section.sectionY() + 4;
                if (index < 0 || index >= sections.size()) continue;
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            assertEquals(section.blocks().get(x, y, z), sections.get(index).blockStates().get(x, y, z),
                                    x + "," + (section.sectionY() * 16 + y) + "," + z);
                        }
                    }
                }
            }
        }
        assertEquals(Block.AIR.stateId(), sections.getFirst().blockStates().get(9, -60 & 15, 9), "the marker is air");
    }

    /**
     * A hidden area inside the chunk, its corners written the other way round: the fine sample 1, 1 it meets
     * takes the lowest top of its ring in the chunk, the other fine samples stay as they are, and the coarse
     * sample of the chunk, which it meets too, takes the ring outside the bounds.
     */
    @Test
    public void aHiddenAreaInsideTheChunkTakesItsRingFromTheChunk() throws Exception {
        final Baked hidden = bake(withHiddenMarker("inside", "synaxis:hidden 7 7 4 4"), "inside");
        final Baked open = bake(withHiddenMarker("outside", "synaxis:hidden 100 100 101 101"), "outside");
        assertEquals(List.of(new HiddenArea(4, 4, 7, 7)), hidden.hidden(), "the corners as min and max");

        final int ring = hidden.ring(1, 1, 1, 1);
        assertTrue(ring >= 3, "the superflat grass is at index 3: " + ring);
        assertEquals(hidden.hidden(ring), hidden.fine(1, 1));
        assertNotEquals(hidden.hidden(ring), open.fine(1, 1));
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                if (x == 1 && z == 1) continue;
                assertEquals(open.pack().biomes().get(open.fine(x, z).biome()), hidden.pack().biomes().get(hidden.fine(x, z).biome()));
                assertEquals(withoutBiome(open.fine(x, z)), withoutBiome(hidden.fine(x, z)), x + "," + z);
            }
        }
        assertEquals(hidden.hidden(0), hidden.coarse().getFirst());
        assertEquals(FarViewPack.coarsen(2, chunk(open)), open.coarse().getFirst(), "nothing hidden in the bounds");
    }

    private static List<Sample> chunk(Baked baked) {
        return IntStream.range(0, 16).mapToObj(i -> baked.fine(i % 4, i / 4)).toList();
    }

    private static Sample withoutBiome(Sample sample) {
        return new Sample(sample.groundState(), sample.fluidState(), sample.groundY(), sample.fluidBottom(), sample.fluidTop(), 0);
    }
}
