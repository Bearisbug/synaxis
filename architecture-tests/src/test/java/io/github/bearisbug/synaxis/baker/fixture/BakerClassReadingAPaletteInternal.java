package io.github.bearisbug.synaxis.baker.fixture;

import io.github.bearisbug.synaxis.instance.palette.Palette;

/**
 * Violation fixture for {@code BakerArchitectureTest}: {@link Palette#singleValue()} is an engine
 * internal that is not in the {@code docs/DESIGN-M3.md} §6 table.
 */
public final class BakerClassReadingAPaletteInternal {
    private BakerClassReadingAPaletteInternal() {
    }

    public static int singleValue(Palette palette) {
        return palette.singleValue();
    }
}
