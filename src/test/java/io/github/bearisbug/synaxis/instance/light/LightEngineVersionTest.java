package io.github.bearisbug.synaxis.instance.light;

import io.github.bearisbug.synaxis.coordinate.BlockFace;
import io.github.bearisbug.synaxis.instance.block.Block;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Binds {@link LightEngine#VERSION} to what the engine outputs (docs/DESIGN-M3.md §24). The fingerprint covers
 * the per state light rules and the engine's light over every parity scene plus a seeded random scene built from
 * every block state. {@code verified-light-versions.txt} records the fingerprint of each version that passed
 * {@code LightParityIntegrationTest}; the baker only bakes with a version listed there.
 * <p>
 * Changing the light output without raising the version turns this red, and so does raising the version without
 * recording it — which is only to be done once the parity test is green.
 */
public class LightEngineVersionTest {

    @Test
    public void theCurrentVersionIsVerifiedWithTheCurrentOutput() throws Exception {
        final Map<Integer, String> verified = verified();
        final String fingerprint = fingerprint();
        assertTrue(verified.containsKey(LightEngine.VERSION), "LightEngine.VERSION " + LightEngine.VERSION
                + " is not in verified-light-versions.txt; once LightParityIntegrationTest is green add the line \""
                + LightEngine.VERSION + " " + fingerprint + "\"");
        assertEquals(verified.get(LightEngine.VERSION), fingerprint, "the light output changed but LightEngine.VERSION"
                + " is still " + LightEngine.VERSION + "; raise it to " + (LightEngine.VERSION + 1)
                + " and, once LightParityIntegrationTest is green, add the line \"" + (LightEngine.VERSION + 1) + " "
                + fingerprint + "\" to verified-light-versions.txt");
    }

    static Map<Integer, String> verified() throws IOException {
        final Map<Integer, String> verified = new HashMap<>();
        try (InputStream in = Objects.requireNonNull(LightEngine.class.getResourceAsStream("verified-light-versions.txt"))) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                if (line.isBlank() || line.startsWith("#")) continue;
                final String[] parts = line.strip().split("\\s+", -1);
                verified.put(Integer.parseInt(parts[0]), parts[1]);
            }
        }
        return verified;
    }

    static String fingerprint() throws IOException, URISyntaxException, NoSuchAlgorithmException {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        // The rules, per state: opacity, emission, whether it stops the sky, and its six faces
        for (int stateId = 0; stateId < Block.statesCount(); stateId++) {
            final Block block = Objects.requireNonNull(Block.fromStateId(stateId));
            digest.update((byte) LightBlocks.opacity(block));
            digest.update((byte) block.lightEmission());
            digest.update((byte) (LightBlocks.skyEdgeOccluded(Block.AIR, block) ? 1 : 0));
            for (BlockFace face : BlockFace.values()) {
                digest.update((byte) ((LightBlocks.occludes(block, Block.AIR, face) ? 1 : 0)
                        | (LightBlocks.occludes(Block.AIR, block, face) ? 2 : 0)));
            }
        }
        // The engine's output
        for (String scene : LightScenes.names()) digest(digest, LightScenes.view(LightScenes.parse(scene)));
        digest(digest, everyState());
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void digest(MessageDigest digest, WorldView view) {
        final LightResult result = LightEngine.computeArea(view, -1, -1, 1, 1);
        for (int cx = -1; cx <= 1; cx++) {
            for (int cz = -1; cz <= 1; cz++) {
                for (int sectionY = -4; sectionY < 20; sectionY++) {
                    digest.update(result.sky(cx, sectionY, cz));
                    digest.update(result.block(cx, sectionY, cz));
                }
            }
        }
    }

    /** Stone under y 0 with every eighth block a random state, and a layer of random states above it. */
    private static WorldView everyState() {
        final ArrayWorldView view = new ArrayWorldView(LightScenes.LOAD_RADIUS, LightScenes.MIN_Y, LightScenes.HEIGHT);
        final Random random = new Random(26_2);
        final int states = Block.statesCount();
        for (int x = -32; x < 48; x++) {
            for (int z = -32; z < 48; z++) {
                for (int y = -64; y < 0; y++) {
                    view.set(x, y, z, random.nextInt(8) == 0 ? randomState(random, states) : Block.STONE);
                }
                for (int y = 0; y < 24; y++) {
                    if (random.nextInt(4) == 0) view.set(x, y, z, randomState(random, states));
                }
            }
        }
        return view;
    }

    private static Block randomState(Random random, int states) {
        return Objects.requireNonNull(Block.fromStateId(random.nextInt(states)));
    }
}
