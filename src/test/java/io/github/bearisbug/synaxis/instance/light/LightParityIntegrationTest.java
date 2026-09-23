package io.github.bearisbug.synaxis.instance.light;

import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.LightingChunk;
import io.github.bearisbug.synaxis.instance.Section;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Nibble-for-nibble parity with the vanilla 26.2 light engine. Each {@code parity/<scene>.scene} is built in
 * chunks -2..2, relit by {@link LightingChunk#relight}, and compared against {@code parity/<scene>.light.gz},
 * which {@code tools/light-oracle/gen.sh} produced by running vanilla's {@code LevelLightEngine} on the same
 * scene. Every {@code *.scene} in that directory runs. Chunks -1..1 and sections -4..3 (y -64..63) are
 * compared, sky and block light both.
 */
@EnvTest
public class LightParityIntegrationTest {
    private static final int LOAD_RADIUS = 2;
    private static final String DIR = "/io/github/bearisbug/synaxis/instance/light/parity/";

    record Op(int x1, int y1, int z1, int x2, int y2, int z2, Block block) {
    }

    static List<String> scenes() throws IOException, URISyntaxException {
        final Path dir = Path.of(Objects.requireNonNull(LightParityIntegrationTest.class.getResource(DIR)).toURI());
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".scene"))
                    .map(name -> name.substring(0, name.length() - ".scene".length()))
                    .sorted().toList();
        }
    }

    @ParameterizedTest
    @MethodSource("scenes")
    public void matchesVanilla(String scene, Env env) throws IOException {
        List<Op> ops = parse(scene);

        Instance instance = env.createEmptyInstance();
        instance.setChunkSupplier(LightingChunk::new);
        instance.setGenerator(unit -> {
            final int minX = unit.absoluteStart().blockX(), maxX = unit.absoluteEnd().blockX() - 1;
            final int minZ = unit.absoluteStart().blockZ(), maxZ = unit.absoluteEnd().blockZ() - 1;
            for (Op op : ops) {
                for (int x = Math.max(op.x1, minX); x <= Math.min(op.x2, maxX); x++) {
                    for (int z = Math.max(op.z1, minZ); z <= Math.min(op.z2, maxZ); z++) {
                        for (int y = op.y1; y <= op.y2; y++) {
                            unit.modifier().setBlock(x, y, z, op.block);
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
        LightingChunk.relight(instance, instance.getChunks());

        List<String> diffs = new ArrayList<>();
        int compared = 0;
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream(DIR + scene + ".light.gz"));
             DataInputStream data = new DataInputStream(new GZIPInputStream(in))) {
            final int radius = data.readInt();
            final int minSection = data.readInt();
            final int maxSection = data.readInt();
            for (int cx = -radius; cx <= radius; cx++) {
                for (int cz = -radius; cz <= radius; cz++) {
                    Chunk chunk = Objects.requireNonNull(instance.getChunk(cx, cz));
                    for (int sectionY = minSection; sectionY <= maxSection; sectionY++) {
                        Section section = chunk.getSection(sectionY);
                        for (String layer : List.of("sky", "block")) {
                            byte[] vanilla = data.readNBytes(2048);
                            Light light = layer.equals("sky") ? section.skyLight() : section.blockLight();
                            for (int index = 0; index < 4096; index++) {
                                final int x = index & 15, z = (index >> 4) & 15, y = index >> 8;
                                final int expected = LightCompute.getLight(vanilla, index);
                                final int actual = light.getLevel(x, y, z);
                                compared++;
                                if (expected == actual) continue;
                                final int bx = cx * 16 + x, by = sectionY * 16 + y, bz = cz * 16 + z;
                                diffs.add("%s %s (%d, %d, %d) %s: vanilla %d, synaxis %d".formatted(scene, layer,
                                        bx, by, bz, instance.getBlock(bx, by, bz).state(), expected, actual));
                            }
                        }
                    }
                }
            }
        }
        diffs.stream().limit(40).forEach(System.err::println);
        assertEquals(0, diffs.size(), diffs.size() + " of " + compared + " nibbles differ from vanilla in " + scene);
    }

    private static List<Op> parse(String scene) throws IOException {
        List<Op> ops = new ArrayList<>();
        try (InputStream in = Objects.requireNonNull(LightParityIntegrationTest.class.getResourceAsStream(DIR + scene + ".scene"))) {
            for (String raw : new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                final String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                final String[] p = line.split("\\s+", -1);
                final int[] n = new int[p.length - 2];
                for (int i = 0; i < n.length; i++) n[i] = Integer.parseInt(p[i + 1]);
                final Block block = block(p[p.length - 1]);
                ops.add(switch (p[0]) {
                    case "fill" -> new Op(Math.min(n[0], n[3]), Math.min(n[1], n[4]), Math.min(n[2], n[5]),
                            Math.max(n[0], n[3]), Math.max(n[1], n[4]), Math.max(n[2], n[5]), block);
                    case "set" -> new Op(n[0], n[1], n[2], n[0], n[1], n[2], block);
                    default -> throw new IllegalArgumentException(line);
                });
            }
        }
        return ops;
    }

    private static Block block(String text) {
        final int bracket = text.indexOf('[');
        final String key = bracket < 0 ? text : text.substring(0, bracket);
        final Block block = Objects.requireNonNull(Block.fromKey(key), key);
        if (bracket < 0) return block;
        final Map<String, String> properties = new HashMap<>();
        for (String pair : text.substring(bracket + 1, text.length() - 1).split(",", -1)) {
            final String[] kv = pair.split("=", 2);
            properties.put(kv[0], kv[1]);
        }
        return block.withProperties(properties);
    }
}
