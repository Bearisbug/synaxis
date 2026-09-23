package io.github.bearisbug.synaxis.instance.light;

import io.github.bearisbug.synaxis.collision.Shape;
import io.github.bearisbug.synaxis.collision.ShapeImpl;
import io.github.bearisbug.synaxis.coordinate.BlockFace;
import io.github.bearisbug.synaxis.instance.block.Block;
import org.jetbrains.annotations.ApiStatus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.Objects;

/**
 * How a block state interacts with light, following the vanilla 26.2 light engine
 * ({@code LightEngine}, {@code ChunkSkyLightSources}).
 * <ul>
 *     <li>Light entering a block loses {@code max(1, lightBlock)} levels; lightBlock comes from the registry.</li>
 *     <li>Only states with {@code useShapeForLightOcclusion} (slabs, stairs, snow layers, farmland, ...) use their
 *     occlusion shape for light; every other state is treated as having no faces. The registry data does not carry
 *     that flag, so the state ids are read from {@code light_shape_states.txt}, generated from the vanilla server by
 *     {@code tools/light-oracle/gen.sh}.</li>
 * </ul>
 */
@ApiStatus.Internal
public final class LightBlocks {
    private static final Shape NO_FACES = Block.AIR.occlusionShape();
    private static final Shape[] SHAPES = loadShapes();

    private LightBlocks() {
    }

    /** Levels lost by light entering {@code block}. */
    public static int opacity(Block block) {
        return Math.max(1, block.lightBlocked());
    }

    /** The shape {@code block} occludes light with. */
    public static Shape shape(Block block) {
        final int stateId = block.stateId();
        return stateId < SHAPES.length ? SHAPES[stateId] : NO_FACES;
    }

    /** Whether light moving from {@code from} towards {@code face} is stopped by the faces between the two blocks. */
    public static boolean occludes(Block from, Block to, BlockFace face) {
        return shape(from).isOccluded(shape(to), face);
    }

    /**
     * Whether the sky column stops between {@code top} and the block right below it, {@code bottom}. Every block
     * from the top of the world down to the first such edge is a full-strength sky source.
     */
    public static boolean skyEdgeOccluded(Block top, Block bottom) {
        return bottom.lightBlocked() != 0 || occludes(top, bottom, BlockFace.BOTTOM);
    }

    private static Shape[] loadShapes() {
        final BitSet stateIds = new BitSet();
        try (InputStream in = Objects.requireNonNull(LightBlocks.class.getResourceAsStream("light_shape_states.txt"));
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                stateIds.set(Integer.parseInt(line.substring(0, line.indexOf(' '))));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        final Shape[] shapes = new Shape[stateIds.length()];
        for (int stateId = 0; stateId < shapes.length; stateId++) {
            if (!stateIds.get(stateId)) {
                shapes[stateId] = NO_FACES;
                continue;
            }
            final Shape shape = Objects.requireNonNull(Block.fromStateId(stateId)).occlusionShape();
            // ShapeImpl#isOccluded lets a light-emitting shape ignore its own faces; vanilla does not.
            shapes[stateId] = shape instanceof ShapeImpl impl ? impl.withLightEmission((byte) 0) : shape;
        }
        return shapes;
    }
}
