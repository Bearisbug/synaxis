package io.github.bearisbug.synaxis.instance.light;

import io.github.bearisbug.synaxis.instance.block.Block;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LightBlocksTest {

    /**
     * light_shape_states.txt names states by vanilla state id. If the registry data is re-vendored without
     * regenerating the list, the ids point at other states; every line must still name the state Synaxis has
     * under that id.
     */
    @Test
    public void shapeStateIdsMatchRegistry() throws IOException {
        List<String> lines;
        try (InputStream in = Objects.requireNonNull(LightBlocks.class.getResourceAsStream("light_shape_states.txt"))) {
            lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
        }
        assertEquals(6762, lines.size());
        final int stateCount = Block.values().stream().mapToInt(block -> block.possibleStates().size()).sum();
        for (String line : lines) {
            final int space = line.indexOf(' ');
            final int id = Integer.parseInt(line.substring(0, space));
            assertTrue(id >= 0 && id < stateCount, () -> line + ": no state id " + id + " among " + stateCount);
            final Block block = Objects.requireNonNull(Block.fromStateId(id), line);
            final String state = line.substring(space + 1);
            final int bracket = state.indexOf('[');
            final Map<String, String> properties = new HashMap<>();
            if (bracket >= 0) {
                for (String pair : state.substring(bracket + 1, state.length() - 1).split(",", -1)) {
                    final String[] kv = pair.split("=", 2);
                    properties.put(kv[0], kv[1]);
                }
            }
            assertEquals(bracket < 0 ? state : state.substring(0, bracket), block.key().asString(), line);
            assertEquals(properties, block.properties(), line);
            assertNotSame(Block.AIR.occlusionShape(), LightBlocks.shape(block), line);
        }
    }

    @Test
    public void onlyLightShapeStatesHaveFaces() {
        assertSame(Block.AIR.occlusionShape(), LightBlocks.shape(Block.STONE));
        assertSame(Block.AIR.occlusionShape(), LightBlocks.shape(Block.WHITE_CARPET));
        assertSame(Block.AIR.occlusionShape(), LightBlocks.shape(Block.OAK_SLAB.withProperty("type", "double")));
        assertEquals(1, LightBlocks.opacity(Block.AIR));
        assertEquals(1, LightBlocks.opacity(Block.WATER));
        assertEquals(15, LightBlocks.opacity(Block.STONE));
    }
}
