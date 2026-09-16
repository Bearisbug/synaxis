package io.github.bearisbug.synaxis.game.rts;

import io.github.bearisbug.synaxis.coordinate.Pos;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A map of the game, as a handful of numbers rather than a file (REQ-M2-010).
 * <p>
 * M2 generates its map in the process from this preset and a seed, and neither reads nor writes a
 * map file: the {@code .synx} format moved to M3 (see {@code docs/DESIGN-M2.md} §28.3). The preset
 * id is what {@code synaxis.app.map} names.
 *
 * @param id     the preset id, the value of {@code synaxis.app.map}
 * @param sizeX  the map width in blocks, spanning {@code [0, sizeX)}
 * @param sizeZ  the map depth in blocks, spanning {@code [0, sizeZ)}
 * @param height the world height in blocks
 * @param seed   the seed the terrain is derived from, so that two runs of the same preset generate
 *               the same map
 */
public record MapPreset(String id, int sizeX, int sizeZ, int height, long seed) {

    /** §9: a map side is a multiple of a chunk and at most this wide. */
    private static final int MAX_SIZE = 512;

    /** The preset of the game, the default value of {@code synaxis.app.map}. */
    public static final MapPreset DEFAULT = new MapPreset("rts-512", 512, 512, 64, 0x5253L);

    /**
     * A small map. It is the second usable preset the release checklist of §23 needs to roll a bad
     * map back with, and it is what the tests generate so that they stay in the second range.
     */
    public static final MapPreset SMALL = new MapPreset("rts-128", 128, 128, 64, 0x5253L);

    private static final List<MapPreset> PRESETS = List.of(DEFAULT, SMALL);

    /**
     * Looks a preset up by id.
     *
     * @param id the preset id
     * @return the preset with that id, null when no preset has it
     */
    public static @Nullable MapPreset byId(String id) {
        for (MapPreset preset : PRESETS) {
            if (preset.id().equals(id)) return preset;
        }
        return null;
    }

    /**
     * The ids a match may be asked to generate, for the log line of an unknown one.
     *
     * @return the known preset ids
     */
    public static List<String> knownIds() {
        return PRESETS.stream().map(MapPreset::id).toList();
    }

    /**
     * The §9 constraints on the generation parameters. They are checked when a match submits the
     * preset, before a single chunk is built, rather than in the constructor: an out of bounds
     * preset has to end the match with {@code map-invalid} and a log line naming the value, not
     * take the process down while the class is being initialized (§11.3 {@code creating → ended}).
     *
     * @return what is wrong with the generation parameters, null when they are valid
     */
    public @Nullable String invalidParameters() {
        final String sizeXProblem = sizeProblem("size_x", sizeX);
        if (sizeXProblem != null) return sizeXProblem;
        final String sizeZProblem = sizeProblem("size_z", sizeZ);
        if (sizeZProblem != null) return sizeZProblem;
        if (height % 16 != 0 || height < 16)
            return "height=" + height + " must be a multiple of 16 and at least 16";
        return null;
    }

    private static @Nullable String sizeProblem(String name, int size) {
        if (size % 16 != 0 || size <= 0 || size > MAX_SIZE)
            return name + "=" + size + " must be a multiple of 16, positive and at most " + MAX_SIZE;
        return null;
    }

    /**
     * The size of the map in chunks, which is how many a match has to generate.
     *
     * @return the number of chunks a full map is made of
     */
    public int chunkCount() {
        return (sizeX / 16) * (sizeZ / 16);
    }

    /**
     * Whether a column is part of the map.
     *
     * @param x a block x
     * @param z a block z
     * @return true when the column is inside the map bounds
     */
    public boolean contains(double x, double z) {
        return x >= 0 && x <= sizeX - 1 && z >= 0 && z <= sizeZ - 1;
    }

    /**
     * Pulls a position back onto the map. This is the boundary constraint of REQ-M2-011: nothing
     * the game moves may leave the generated area, because outside it there is no chunk and never
     * will be, auto chunk loading being off for the whole match. The height is left alone.
     *
     * @param position the position to constrain
     * @return the position, with x and z inside {@code [0, size - 1]}
     */
    public Pos clamp(Pos position) {
        return position.withCoord(
                Math.max(0, Math.min(sizeX - 1, position.x())),
                position.y(),
                Math.max(0, Math.min(sizeZ - 1, position.z())));
    }
}
