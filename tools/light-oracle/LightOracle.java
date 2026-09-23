import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

/**
 * Runs the vanilla 26.2 light engine ({@link LevelLightEngine}) on a hand-built scene, with no world, no
 * server and no disk, and writes the result as the truth fixture for Synaxis' LightParityIntegrationTest.
 * Also dumps the block states that use their shape for light occlusion, which Synaxis' registry data does
 * not carry. Run it through gen.sh, which puts the vanilla server jar and its libraries on the class path.
 *
 * <pre>
 *   java LightOracle.java shapes &lt;out.txt&gt;
 *   java LightOracle.java scene &lt;in.scene&gt; &lt;out.light.gz&gt;
 * </pre>
 */
public final class LightOracle {
    // Must match LightParityIntegrationTest: the scene lives in chunks -2..2, light is compared in -1..1
    // for sections 0..3. The outer ring is 16 blocks wide, more than light can travel, so however vanilla
    // and Synaxis treat the unloaded world past it cannot reach the compared chunks.
    static final int LOAD_RADIUS = 2;
    static final int COMPARE_RADIUS = 1;
    static final int MIN_SECTION = 0;
    static final int MAX_SECTION = 3;
    static final int MIN_Y = -64;
    static final int HEIGHT = 384;

    public static void main(String[] args) throws IOException {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        switch (args[0]) {
            case "shapes" -> writeShapeStates(Path.of(args[1]));
            case "scene" -> writeScene(Path.of(args[1]), Path.of(args[2]));
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    static void writeShapeStates(Path out) throws IOException {
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            writer.println("# Block states that use their occlusion shape for light (BlockState#useShapeForLightOcclusion");
            writer.println("# and canOcclude). Every other state lets light through all six faces and is stopped only by");
            writer.println("# its lightBlock. Generated from the vanilla 26.2 server by tools/light-oracle/gen.sh; do not edit.");
            writer.println("# <stateId> <block>[<properties>]");
            for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
                if (!state.canOcclude() || !state.useShapeForLightOcclusion()) continue;
                writer.println(Block.BLOCK_STATE_REGISTRY.getId(state) + " " + describe(state));
            }
        }
    }

    static String describe(BlockState state) {
        String key = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (state.getProperties().isEmpty()) return key;
        return key + state.getProperties().stream()
                .map(property -> property.getName() + "=" + valueName(state, property))
                .collect(Collectors.joining(",", "[", "]"));
    }

    static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    static void writeScene(Path sceneFile, Path out) throws IOException {
        Scene scene = Scene.parse(Files.readAllLines(sceneFile, StandardCharsets.UTF_8));
        LevelHeightAccessor height = LevelHeightAccessor.create(MIN_Y, HEIGHT);

        SceneChunk[][] chunks = new SceneChunk[LOAD_RADIUS * 2 + 1][LOAD_RADIUS * 2 + 1];
        for (int cx = -LOAD_RADIUS; cx <= LOAD_RADIUS; cx++) {
            for (int cz = -LOAD_RADIUS; cz <= LOAD_RADIUS; cz++) {
                chunks[cx + LOAD_RADIUS][cz + LOAD_RADIUS] = new SceneChunk(scene, height, cx, cz);
            }
        }
        BlockGetter level = new SceneGetter(scene, height);
        LightChunkGetter getter = new LightChunkGetter() {
            @Override
            public LightChunk getChunkForLighting(int x, int z) {
                if (Math.abs(x) > LOAD_RADIUS || Math.abs(z) > LOAD_RADIUS) return null;
                return chunks[x + LOAD_RADIUS][z + LOAD_RADIUS];
            }

            @Override
            public BlockGetter getLevel() {
                return level;
            }
        };

        // Same order as ThreadedLevelLightEngine#initializeLight then #lightChunk for freshly generated chunks.
        LevelLightEngine engine = new LevelLightEngine(getter, true, true);
        for (SceneChunk[] row : chunks) {
            for (SceneChunk chunk : row) {
                for (int sectionY = height.getMinSectionY(); sectionY <= height.getMaxSectionY(); sectionY++) {
                    if (!chunk.sectionEmpty(sectionY)) {
                        engine.updateSectionStatus(SectionPos.of(chunk.x, sectionY, chunk.z), false);
                    }
                }
            }
        }
        engine.runLightUpdates();
        for (SceneChunk[] row : chunks) {
            for (SceneChunk chunk : row) {
                ChunkPos pos = new ChunkPos(chunk.x, chunk.z);
                engine.setLightEnabled(pos, false);
                engine.retainData(pos, false);
            }
        }
        engine.runLightUpdates();
        for (SceneChunk[] row : chunks) {
            for (SceneChunk chunk : row) {
                engine.propagateLightSources(new ChunkPos(chunk.x, chunk.z));
            }
        }
        while (engine.hasLightWork()) engine.runLightUpdates();

        try (OutputStream file = Files.newOutputStream(out);
             DataOutputStream data = new DataOutputStream(new GZIPOutputStream(file))) {
            data.writeInt(COMPARE_RADIUS);
            data.writeInt(MIN_SECTION);
            data.writeInt(MAX_SECTION);
            for (int cx = -COMPARE_RADIUS; cx <= COMPARE_RADIUS; cx++) {
                for (int cz = -COMPARE_RADIUS; cz <= COMPARE_RADIUS; cz++) {
                    for (int sectionY = MIN_SECTION; sectionY <= MAX_SECTION; sectionY++) {
                        for (LightLayer layer : List.of(LightLayer.SKY, LightLayer.BLOCK)) {
                            data.write(nibbles(engine, layer, cx, sectionY, cz));
                        }
                    }
                }
            }
        }
    }

    /** One section in Synaxis' layout: index {@code x | z << 4 | y << 8}, even index in the low nibble. */
    static byte[] nibbles(LevelLightEngine engine, LightLayer layer, int cx, int sectionY, int cz) {
        byte[] out = new byte[2048];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int level = engine.getLayerListener(layer).getLightValue(
                            pos.set(cx * 16 + x, sectionY * 16 + y, cz * 16 + z));
                    int index = x | (z << 4) | (y << 8);
                    out[index >> 1] |= (byte) (level << ((index & 1) << 2));
                }
            }
        }
        return out;
    }

    /** Scene file: {@code fill x1 y1 z1 x2 y2 z2 <state>} (inclusive, like /fill) and {@code set x y z <state>}; later lines win. */
    record Scene(List<Op> ops) {
        record Op(int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
        }

        static Scene parse(List<String> lines) {
            List<Op> ops = new java.util.ArrayList<>();
            for (String raw : lines) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\s+");
                switch (parts[0]) {
                    case "fill" -> ops.add(new Op(
                            Math.min(i(parts[1]), i(parts[4])), Math.min(i(parts[2]), i(parts[5])), Math.min(i(parts[3]), i(parts[6])),
                            Math.max(i(parts[1]), i(parts[4])), Math.max(i(parts[2]), i(parts[5])), Math.max(i(parts[3]), i(parts[6])),
                            state(parts[7])));
                    case "set" -> ops.add(new Op(i(parts[1]), i(parts[2]), i(parts[3]),
                            i(parts[1]), i(parts[2]), i(parts[3]), state(parts[4])));
                    default -> throw new IllegalArgumentException(line);
                }
            }
            return new Scene(List.copyOf(ops));
        }

        static int i(String s) {
            return Integer.parseInt(s);
        }

        static BlockState state(String text) {
            int bracket = text.indexOf('[');
            String name = bracket < 0 ? text : text.substring(0, bracket);
            Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(name))
                    .orElseThrow(() -> new IllegalArgumentException("unknown block " + name));
            BlockState state = block.defaultBlockState();
            if (bracket >= 0) {
                for (String pair : text.substring(bracket + 1, text.length() - 1).split(",")) {
                    String[] kv = pair.split("=");
                    Property<?> property = block.getStateDefinition().getProperty(kv[0]);
                    if (property == null) throw new IllegalArgumentException(text + ": no property " + kv[0]);
                    state = with(state, property, kv[1]);
                }
            }
            return state;
        }

        static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> property, String value) {
            return state.setValue(property, property.getValue(value)
                    .orElseThrow(() -> new IllegalArgumentException(property.getName() + "=" + value)));
        }

        BlockState at(int x, int y, int z) {
            BlockState result = Blocks.AIR.defaultBlockState();
            for (Op op : ops) {
                if (x >= op.x1 && x <= op.x2 && y >= op.y1 && y <= op.y2 && z >= op.z1 && z <= op.z2) result = op.state;
            }
            return result;
        }
    }

    static class SceneGetter implements BlockGetter {
        final Scene scene;
        final LevelHeightAccessor height;

        SceneGetter(Scene scene, LevelHeightAccessor height) {
            this.scene = scene;
            this.height = height;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return scene.at(pos.getX(), pos.getY(), pos.getZ());
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return height.getHeight();
        }

        @Override
        public int getMinY() {
            return height.getMinY();
        }
    }

    /** One chunk of the scene, with block states cached so the engine does not rescan the op list per lookup. */
    static final class SceneChunk extends SceneGetter implements LightChunk {
        final int x;
        final int z;
        final BlockState[] states;
        final ChunkSkyLightSources skySources;

        SceneChunk(Scene scene, LevelHeightAccessor height, int x, int z) {
            super(scene, height);
            this.x = x;
            this.z = z;
            this.states = new BlockState[16 * 16 * HEIGHT];
            for (int ly = 0; ly < HEIGHT; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        states[index(lx, ly, lz)] = scene.at(x * 16 + lx, MIN_Y + ly, z * 16 + lz);
                    }
                }
            }
            // ChunkSkyLightSources#fillFrom needs a full ChunkAccess. Feeding every block top-down through
            // #update finds the same highest occluding edge per column with the same isEdgeOccluded rule.
            this.skySources = new ChunkSkyLightSources(height);
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    for (int y = MIN_Y + HEIGHT - 1; y >= MIN_Y; y--) {
                        if (skySources.update(this, lx, y, lz)) break;
                    }
                }
            }
        }

        static int index(int lx, int ly, int lz) {
            return (ly << 8) | (lz << 4) | lx;
        }

        boolean sectionEmpty(int sectionY) {
            int base = (sectionY * 16 - MIN_Y);
            for (int ly = base; ly < base + 16; ly++) {
                for (int i = 0; i < 256; i++) {
                    if (!states[(ly << 8) | i].isAir()) return false;
                }
            }
            return true;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            int ly = pos.getY() - MIN_Y;
            if (ly < 0 || ly >= HEIGHT) return Blocks.AIR.defaultBlockState();
            return states[index(pos.getX() & 15, ly, pos.getZ() & 15)];
        }

        @Override
        public void findBlockLightSources(BiConsumer<BlockPos, BlockState> consumer) {
            for (int ly = 0; ly < HEIGHT; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        BlockState state = states[index(lx, ly, lz)];
                        if (state.getLightEmission() > 0) {
                            consumer.accept(new BlockPos(x * 16 + lx, MIN_Y + ly, z * 16 + lz), state);
                        }
                    }
                }
            }
        }

        @Override
        public ChunkSkyLightSources getSkyLightSources() {
            return skySources;
        }
    }
}
