package io.github.bearisbug.synaxis.instance.light;

import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.LightingChunk;
import io.github.bearisbug.synaxis.instance.Section;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

import static io.github.bearisbug.synaxis.instance.light.LightScenes.DIR;
import static io.github.bearisbug.synaxis.instance.light.LightScenes.LOAD_RADIUS;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Nibble-for-nibble parity with the vanilla 26.2 light engine. Each {@code parity/<scene>.scene} is built in
 * chunks -2..2 and compared against {@code parity/<scene>.light.gz}, which {@code tools/light-oracle/gen.sh}
 * produced by running vanilla's {@code LevelLightEngine} on the same scene. Every {@code *.scene} in that
 * directory runs. Chunks -1..1 and sections -4..3 (y -64..63) are compared, sky and block light both.
 * <p>
 * Two paths light the scene: {@link LightEngine#computeArea} on a plain {@link WorldView} with no instance, and
 * {@link LightingChunk#relight}, which delegates to the engine.
 */
@EnvTest
public class LightParityIntegrationTest {

    @FunctionalInterface
    private interface Levels {
        int level(boolean sky, int x, int y, int z);
    }

    static List<String> scenes() throws IOException, URISyntaxException {
        return LightScenes.names();
    }

    @ParameterizedTest
    @MethodSource("scenes")
    public void lightEngineMatchesVanilla(String scene) throws IOException {
        final LightResult result = LightEngine.computeArea(LightScenes.view(LightScenes.parse(scene)), -1, -1, 1, 1);
        assertMatchesVanilla(scene, (sky, x, y, z) -> sky ? result.skyLevel(x, y, z) : result.blockLevel(x, y, z));
    }

    @ParameterizedTest
    @MethodSource("scenes")
    public void relightMatchesVanilla(String scene, Env env) throws IOException {
        final Instance instance = build(scene, env);
        LightingChunk.relight(instance, instance.getChunks());
        assertMatchesVanilla(scene, (sky, x, y, z) -> {
            final Chunk chunk = Objects.requireNonNull(instance.getChunkAt(x, z));
            final Section section = chunk.getSectionAt(y);
            return (sky ? section.skyLight() : section.blockLight()).getLevel(x & 15, y & 15, z & 15);
        });
    }

    private static Instance build(String scene, Env env) throws IOException {
        final List<LightScenes.Op> ops = LightScenes.parse(scene);
        Instance instance = env.createEmptyInstance();
        instance.setChunkSupplier(LightingChunk::new);
        instance.setGenerator(unit -> {
            final int minX = unit.absoluteStart().blockX(), maxX = unit.absoluteEnd().blockX() - 1;
            final int minZ = unit.absoluteStart().blockZ(), maxZ = unit.absoluteEnd().blockZ() - 1;
            for (LightScenes.Op op : ops) {
                for (int x = Math.max(op.x1(), minX); x <= Math.min(op.x2(), maxX); x++) {
                    for (int z = Math.max(op.z1(), minZ); z <= Math.min(op.z2(), maxZ); z++) {
                        for (int y = op.y1(); y <= op.y2(); y++) {
                            unit.modifier().setBlock(x, y, z, op.block());
                        }
                    }
                }
            }
        });
        for (int x = -LOAD_RADIUS; x <= LOAD_RADIUS; x++) {
            for (int z = -LOAD_RADIUS; z <= LOAD_RADIUS; z++) {
                instance.loadChunk(x, z).join();
            }
        }
        return instance;
    }

    private void assertMatchesVanilla(String scene, Levels levels) throws IOException {
        final WorldView blocks = LightScenes.view(LightScenes.parse(scene));
        List<String> diffs = new ArrayList<>();
        int compared = 0;
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream(DIR + scene + ".light.gz"));
             DataInputStream data = new DataInputStream(new GZIPInputStream(in))) {
            final int radius = data.readInt();
            final int minSection = data.readInt();
            final int maxSection = data.readInt();
            for (int cx = -radius; cx <= radius; cx++) {
                for (int cz = -radius; cz <= radius; cz++) {
                    for (int sectionY = minSection; sectionY <= maxSection; sectionY++) {
                        for (String layer : List.of("sky", "block")) {
                            byte[] vanilla = data.readNBytes(2048);
                            for (int index = 0; index < 4096; index++) {
                                final int bx = cx * 16 + (index & 15), by = sectionY * 16 + (index >> 8), bz = cz * 16 + ((index >> 4) & 15);
                                final int expected = LightCompute.getLight(vanilla, index);
                                final int actual = levels.level(layer.equals("sky"), bx, by, bz);
                                compared++;
                                if (expected == actual) continue;
                                diffs.add("%s %s (%d, %d, %d) %s: vanilla %d, synaxis %d".formatted(scene, layer,
                                        bx, by, bz, blocks.getBlock(bx, by, bz).state(), expected, actual));
                            }
                        }
                    }
                }
            }
        }
        diffs.stream().limit(40).forEach(System.err::println);
        assertEquals(0, diffs.size(), diffs.size() + " of " + compared + " nibbles differ from vanilla in " + scene);
    }
}
