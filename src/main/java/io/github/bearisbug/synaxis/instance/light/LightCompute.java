package io.github.bearisbug.synaxis.instance.light;

import it.unimi.dsi.fastutil.shorts.ShortArrayFIFOQueue;
import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.coordinate.BlockFace;
import io.github.bearisbug.synaxis.instance.palette.Palette;
import io.github.bearisbug.synaxis.coordinate.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;

import static io.github.bearisbug.synaxis.coordinate.CoordConversion.SECTION_BLOCK_COUNT;

public final class LightCompute {
    static final Direction[] DIRECTIONS = Direction.values();
    // FACES[i] is the face of DIRECTIONS[i]: BlockFace declares its constants in Direction's order
    static final BlockFace[] FACES = BlockFace.values();
    static final int LIGHT_LENGTH = SECTION_BLOCK_COUNT / 2;
    static final int SECTION_SIZE = 16;

    /**
     * No light data, sent as an empty mask: {@link Light#array} of a section never computed, or of a dark one (which
     * may also be {@link #EMPTY_CONTENT}).
     */
    public static final byte[] UNSET_CONTENT = new byte[0];
    // Shared sentinel arrays compared by identity; never written
    @SuppressWarnings("MutablePublicArray")
    public static final byte[] EMPTY_CONTENT = new byte[LIGHT_LENGTH];
    @SuppressWarnings("MutablePublicArray")
    public static final byte[] CONTENT_FULLY_LIT = new byte[LIGHT_LENGTH];

    static {
        Arrays.fill(CONTENT_FULLY_LIT, (byte) -1);
    }

    /** A copy of {@code content}, or the shared constant equal to it; null or empty is {@link #EMPTY_CONTENT}. */
    static byte[] lazyArray(byte @Nullable [] content) {
        if (content == null || content.length == 0) return EMPTY_CONTENT;
        else if (Arrays.equals(content, EMPTY_CONTENT)) return EMPTY_CONTENT;
        else if (Arrays.equals(content, CONTENT_FULLY_LIT)) return CONTENT_FULLY_LIT;
        else return content.clone();
    }

    static ShortArrayFIFOQueue buildExternalQueue(Palette blockPalette,
                                                  Point[] neighbors, byte @Nullable [] content,
                                                  Light.LightLookup lightLookup,
                                                  Light.PaletteLookup paletteLookup) {
        ShortArrayFIFOQueue lightSources = new ShortArrayFIFOQueue();
        for (int i = 0; i < neighbors.length; i++) {
            Point neighborSection = neighbors[i];
            if (neighborSection == null) continue;
            Palette otherPalette = paletteLookup.palette(neighborSection.blockX(), neighborSection.blockY(), neighborSection.blockZ());
            if (otherPalette == null) continue;
            Light otherLight = lightLookup.light(neighborSection.blockX(), neighborSection.blockY(), neighborSection.blockZ());
            if (otherLight == null) continue;

            final BlockFace face = FACES[i];
            final int layer = borderLayer(face);
            for (int a = 0; a < SECTION_SIZE; a++) {
                for (int b = 0; b < SECTION_SIZE; b++) {
                    // The cell of this section's border and the one across it in the neighbour's
                    final int posTo = borderIndex(face, layer, a, b);
                    final int posFrom = borderIndex(face, 15 - layer, a, b);
                    final int fromLevel = otherLight.getLevel(posFrom & 15, posFrom >> 8, (posFrom >> 4) & 15);
                    if (fromLevel <= 1) continue;

                    final Block blockTo = getBlock(blockPalette, posTo & 15, posTo >> 8, (posTo >> 4) & 15);
                    final int reached = fromLevel - LightBlocks.opacity(blockTo);
                    if (reached <= 0) continue;
                    if (content != null && reached < getLight(content, posTo)) continue;

                    final Block blockFrom = getBlock(otherPalette, posFrom & 15, posFrom >> 8, (posFrom >> 4) & 15);
                    if (LightBlocks.occludes(blockFrom, blockTo, face.getOppositeFace())) continue;

                    lightSources.enqueue((short) (posTo | (reached << 12)));
                }
            }
        }
        return lightSources;
    }

    /**
     * Spreads light inside one section, breadth first, from the queued cells.
     *
     * @param blockPalette the section's blocks
     * @param lightPre     cells as shorts {@code [4 bit level][4 bit y][4 bit z][4 bit x]}
     * @return the section's nibble array; {@link #EMPTY_CONTENT}, which must not be written, when the queue is empty
     */
    static byte [] compute(Palette blockPalette, ShortArrayFIFOQueue lightPre) {
        if (lightPre.isEmpty()) return EMPTY_CONTENT;

        final byte[] lightArray = new byte[LIGHT_LENGTH];

        final ShortArrayFIFOQueue lightSources = new ShortArrayFIFOQueue();

        while (!lightPre.isEmpty()) {
            final int index = lightPre.dequeueShort();

            final int newLightLevel = (index >> 12) & 15;
            final int newIndex = index & 0xFFF;

            final int oldLightLevel = getLight(lightArray, newIndex);

            if (oldLightLevel < newLightLevel) {
                placeLight(lightArray, newIndex, newLightLevel);
                lightSources.enqueue((short) index);
            }
        }

        while (!lightSources.isEmpty()) {
            final int index = lightSources.dequeueShort();
            final int x = index & 15;
            final int z = (index >> 4) & 15;
            final int y = (index >> 8) & 15;
            final int lightLevel = (index >> 12) & 15;
            if (lightLevel <= 1) continue;

            final Block currentBlock = getBlock(blockPalette, x, y, z);
            for (Direction direction : DIRECTIONS) {
                final int xO = x + direction.normalX();
                final int yO = y + direction.normalY();
                final int zO = z + direction.normalZ();

                // Stay inside the section
                if (xO < 0 || xO >= SECTION_SIZE || yO < 0 || yO >= SECTION_SIZE || zO < 0 || zO >= SECTION_SIZE) {
                    continue;
                }

                final int newIndex = xO | (zO << 4) | (yO << 8);
                final int oldLightLevel = getLight(lightArray, newIndex);
                if (oldLightLevel >= lightLevel - 1) continue;

                final Block propagatedBlock = getBlock(blockPalette, xO, yO, zO);
                final int newLightLevel = lightLevel - LightBlocks.opacity(propagatedBlock);
                if (oldLightLevel >= newLightLevel) continue;

                if (LightBlocks.occludes(currentBlock, propagatedBlock, BlockFace.fromDirection(direction))) continue;

                placeLight(lightArray, newIndex, newLightLevel);
                lightSources.enqueue((short) (newIndex | (newLightLevel << 12)));
            }
        }
        return lightArray;
    }

    private static void placeLight(byte[] light, int index, int value) {
        final int shift = (index & 1) << 2;
        final int i = index >>> 1;
        light[i] = (byte) ((light[i] & (0xF0 >>> shift)) | (value << shift));
    }

    static int getLight(byte[] light, int x, int y, int z) {
        return getLight(light, x | (z << 4) | (y << 8));
    }

    static int getLight(byte[] light, int index) {
        if (index >>> 1 >= light.length) return 0;
        final int value = light[index >>> 1];
        return ((value >>> ((index & 1) << 2)) & 0xF);
    }

    public static Block getBlock(Palette palette, int x, int y, int z) {
        return Objects.requireNonNullElse(Block.fromStateId(palette.get(x, y, z)), Block.AIR);
    }

    /**
     * Cell-wise max of two nibble arrays, a null {@code content1} counting as all 0. May return an argument or a
     * shared constant: do not write the result.
     */
    public static byte[] bake(byte @Nullable [] content1, byte[] content2) {
        if (content1 == CONTENT_FULLY_LIT || content2 == CONTENT_FULLY_LIT) return CONTENT_FULLY_LIT;
        if (content1 == null || content1 == content2) return content2;
        if (Arrays.equals(content1, EMPTY_CONTENT) && Arrays.equals(content2, EMPTY_CONTENT)) return EMPTY_CONTENT;
        if (Arrays.equals(content1, CONTENT_FULLY_LIT) || Arrays.equals(content2, CONTENT_FULLY_LIT)) return CONTENT_FULLY_LIT;

        final byte[] lightMax = new byte[LIGHT_LENGTH];
        for (int i = 0; i < LIGHT_LENGTH; i++) {
            final int c1 = content1[i], c2 = content2[i];
            lightMax[i] = (byte) (Math.max(c1 & 0x0F, c2 & 0x0F) | Math.max(c1 & 0xF0, c2 & 0xF0));
        }
        return lightMax;
    }

    /**
     * Whether no cell of this section's border towards {@code face} is brighter in {@code contentPropagationTemp}
     * than in {@code content} and {@code contentPropagation} together, so the neighbour across {@code face} needs no
     * update.
     */
    public static boolean compareBorders(byte @Nullable [] content, byte @Nullable [] contentPropagation,
                                         byte[] contentPropagationTemp, BlockFace face) {
        final int layer = borderLayer(face);
        for (int a = 0; a < SECTION_SIZE; a++) {
            for (int b = 0; b < SECTION_SIZE; b++) {
                final int index = borderIndex(face, layer, a, b);
                final int valueFrom = Math.max(level(content, index), level(contentPropagation, index));
                if (valueFrom < getLight(contentPropagationTemp, index)) return false;
            }
        }
        return true;
    }

    private static int level(byte @Nullable [] light, int index) {
        return light == null ? 0 : getLight(light, index);
    }

    /** The layer of a section, along the axis of {@code face}, that borders the section across {@code face}. */
    private static int borderLayer(BlockFace face) {
        return switch (face) {
            case WEST, BOTTOM, NORTH -> 0;
            case EAST, TOP, SOUTH -> 15;
        };
    }

    /** Index {@code x | z << 4 | y << 8} of cell {@code (a, b)} of layer {@code layer} along the axis of {@code face}. */
    private static int borderIndex(BlockFace face, int layer, int a, int b) {
        return switch (face) {
            case NORTH, SOUTH -> a | layer << 4 | b << 8;
            case WEST, EAST -> layer | b << 4 | a << 8;
            case BOTTOM, TOP -> a | b << 4 | layer << 8;
        };
    }
}
