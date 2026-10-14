package io.github.bearisbug.synaxis.baker.anvil;

import net.kyori.adventure.nbt.CompoundBinaryTag;

import java.util.List;
import java.util.Set;

/**
 * A full source chunk after the DataVersion and status gates and after the data markers were applied.
 *
 * @param chunkX        chunk x
 * @param chunkZ        chunk z
 * @param lightOn       the source's {@code isLightOn}: vanilla finished lighting it
 * @param sections      sections carrying block states, in ascending y
 * @param blockEntities every block entity, markers merged, in ascending (section, index)
 * @param entities      the compounds of {@code entities/} for this chunk, each whole (id, Pos, all tags)
 * @param hiddenAreas   areas marked hidden inside this chunk
 * @param markerCells   {@link #cellKey} of every data marker turned into air and of the block below it, the cells
 *                      whose light the markers change
 */
public record AnvilChunk(int chunkX, int chunkZ, boolean lightOn, List<AnvilSection> sections,
                         List<AnvilBlockEntity> blockEntities, List<CompoundBinaryTag> entities,
                         List<HiddenArea> hiddenAreas, Set<Long> markerCells) {
    public AnvilChunk {
        markerCells = Set.copyOf(markerCells);
    }

    /**
     * A block of a chunk: {@code sectionY} above the 12 bits of {@link AnvilBlockEntity#index}, so keys order by
     * section, then index.
     */
    public static long cellKey(int sectionY, int index) {
        return ((long) sectionY << 12) | index;
    }
}
