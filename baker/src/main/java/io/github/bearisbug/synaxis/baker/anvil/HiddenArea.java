package io.github.bearisbug.synaxis.baker.anvil;

/**
 * An area a {@code synaxis:hidden <x1> <z1> <x2> <z2>} data marker keeps out of the far view pack,
 * in block coordinates, both corners inclusive as written in the marker.
 */
public record HiddenArea(int x1, int z1, int x2, int z2) {
}
