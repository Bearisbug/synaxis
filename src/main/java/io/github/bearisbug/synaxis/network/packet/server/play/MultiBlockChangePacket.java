package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import java.util.Arrays;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.LONG;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_LONG_ARRAY;

public record MultiBlockChangePacket(long chunkSectionPosition, long[] blocks) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<MultiBlockChangePacket> SERIALIZER = NetworkBufferTemplate.template(
            LONG, MultiBlockChangePacket::chunkSectionPosition,
            VAR_LONG_ARRAY, MultiBlockChangePacket::blocks,
            MultiBlockChangePacket::new);

    public MultiBlockChangePacket {
        blocks = blocks.clone();
    }

    public MultiBlockChangePacket(int chunkX, int section, int chunkZ, long[] blocks) {
        this(((long) (chunkX & 0x3FFFFF) << 42) | (section & 0xFFFFF) | ((long) (chunkZ & 0x3FFFFF) << 20), blocks);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MultiBlockChangePacket(long sectionPosition, long[] blocks1))) return false;
        return chunkSectionPosition() == sectionPosition && Arrays.equals(blocks(), blocks1);
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(chunkSectionPosition());
        result = 31 * result + Arrays.hashCode(blocks());
        return result;
    }
}
