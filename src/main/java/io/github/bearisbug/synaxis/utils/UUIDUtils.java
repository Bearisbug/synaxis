package io.github.bearisbug.synaxis.utils;

import net.kyori.adventure.nbt.IntArrayBinaryTag;
import org.jetbrains.annotations.ApiStatus;

import java.util.UUID;

/**
 * An utilities class for {@link UUID}.
 */
@ApiStatus.Internal
public final class UUIDUtils {
    public static UUID fromNbt(IntArrayBinaryTag tag) {
        return intArrayToUuid(tag.value());
    }

    public static IntArrayBinaryTag toNbt(UUID uuid) {
        return IntArrayBinaryTag.intArrayBinaryTag(uuidToIntArray(uuid));
    }

    public static int[] uuidToIntArray(UUID uuid) {
        final long uuidMost = uuid.getMostSignificantBits();
        final long uuidLeast = uuid.getLeastSignificantBits();
        return new int[]{
                (int) (uuidMost >> 32),
                (int) uuidMost,
                (int) (uuidLeast >> 32),
                (int) uuidLeast
        };
    }

    public static UUID intArrayToUuid(int[] array) {
        final long uuidMost = ((long) array[0] << 32) | (array[1] & 0xFFFFFFFFL);
        final long uuidLeast = ((long) array[2] << 32) | (array[3] & 0xFFFFFFFFL);

        return new UUID(uuidMost, uuidLeast);
    }
}
