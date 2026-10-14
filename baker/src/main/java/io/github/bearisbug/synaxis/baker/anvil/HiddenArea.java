package io.github.bearisbug.synaxis.baker.anvil;

/**
 * An area a {@code synaxis:hidden <x1> <z1> <x2> <z2>} data marker keeps out of the far view pack, in block
 * coordinates, both corners inclusive. {@link AnvilChunk#hiddenAreas()} keeps the corners as the marker wrote
 * them; {@code BakeSource#hiddenAreas()} hands them out as min and max.
 */
public record HiddenArea(int x1, int z1, int x2, int z2) {
}
