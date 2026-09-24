package io.github.bearisbug.synaxis.instance.light;

import io.github.bearisbug.synaxis.instance.block.Block;

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

/**
 * The hand written light scenes under {@code parity/}: each {@code <scene>.scene} is a list of {@code fill} and
 * {@code set} lines, and {@code tools/light-oracle/gen.sh} turned it into {@code <scene>.light.gz} by running the
 * vanilla 26.2 light engine on it. The scene lives in chunks -2..2, y -64..319.
 */
final class LightScenes {
    static final String DIR = "/io/github/bearisbug/synaxis/instance/light/parity/";
    static final int LOAD_RADIUS = 2;
    static final int MIN_Y = -64;
    static final int HEIGHT = 384;

    record Op(int x1, int y1, int z1, int x2, int y2, int z2, Block block) {
    }

    private LightScenes() {
    }

    static List<String> names() throws IOException, URISyntaxException {
        final Path dir = Path.of(Objects.requireNonNull(LightScenes.class.getResource(DIR)).toURI());
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".scene"))
                    .map(name -> name.substring(0, name.length() - ".scene".length()))
                    .sorted().toList();
        }
    }

    /** The scene's blocks in a plain {@link WorldView}, no instance involved. */
    static ArrayWorldView view(List<Op> ops) {
        final ArrayWorldView view = new ArrayWorldView(LOAD_RADIUS, MIN_Y, HEIGHT);
        for (Op op : ops) {
            for (int x = op.x1; x <= op.x2; x++) {
                for (int z = op.z1; z <= op.z2; z++) {
                    for (int y = op.y1; y <= op.y2; y++) {
                        view.set(x, y, z, op.block);
                    }
                }
            }
        }
        return view;
    }

    static List<Op> parse(String scene) throws IOException {
        List<Op> ops = new ArrayList<>();
        try (InputStream in = Objects.requireNonNull(LightScenes.class.getResourceAsStream(DIR + scene + ".scene"))) {
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
