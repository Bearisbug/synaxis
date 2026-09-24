package io.github.bearisbug.synaxis.game.rts;

import io.github.bearisbug.synaxis.baker.BakeGeneratorProvider;
import io.github.bearisbug.synaxis.baker.GeneratorPreset;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import org.jetbrains.annotations.Nullable;

/**
 * The M2 map presets as baker sources, {@code generator:rts-512:<seed>} ({@code docs/DESIGN-M3.md} §6): the
 * same {@link MapGenerator} a match runs, over the preset's chunks, in a world from y 0 as high as the preset.
 * The baker finds this class through {@link java.util.ServiceLoader}; the plugin never runs it.
 */
public final class RtsBakeGenerators implements BakeGeneratorProvider {
    @Override
    public @Nullable GeneratorPreset preset(String id, long seed) {
        final MapPreset known = MapPreset.byId(id);
        if (known == null) return null;
        final MapPreset preset = new MapPreset(known.id(), known.sizeX(), known.sizeZ(), known.height(), seed);
        return new GeneratorPreset(new MapGenerator(new MapTerrain(preset)), 0, preset.height(),
                new SynxManifest.Bounds(0, 0, preset.sizeX() / 16 - 1, preset.sizeZ() / 16 - 1));
    }
}
