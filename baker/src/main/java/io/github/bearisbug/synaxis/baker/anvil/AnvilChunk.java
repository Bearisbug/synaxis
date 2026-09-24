package io.github.bearisbug.synaxis.baker.anvil;

import net.kyori.adventure.nbt.CompoundBinaryTag;

import java.util.List;

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
 */
public record AnvilChunk(int chunkX, int chunkZ, boolean lightOn, List<AnvilSection> sections,
                         List<AnvilBlockEntity> blockEntities, List<CompoundBinaryTag> entities,
                         List<HiddenArea> hiddenAreas) {
}
