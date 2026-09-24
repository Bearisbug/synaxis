package io.github.bearisbug.synaxis.synx.fixture;

import io.github.bearisbug.synaxis.instance.LightingChunk;
import io.github.bearisbug.synaxis.instance.palette.Palette;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.PacketWriting;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;

/**
 * Stands in for a {@code :synx} class that frames segment ① through the engine's internal
 * {@code PacketWriting}, reads a palette through the internal {@code Palette#singleValue()} and
 * reaches for {@code LightingChunk}: none of the three is on the internal surface table of
 * {@code docs/DESIGN-M3.md} §6. Only ever imported as a synthetic {@code JavaClasses};
 * the scan of the {@code synx} package leaves it out through {@code DoNotIncludeTests}.
 */
public final class SynxClassFramingThroughTheEngine {

    private SynxClassFramingThroughTheEngine() {
    }

    @SuppressWarnings("unused")
    static void frame(NetworkBuffer buffer, ChunkDataPacket packet, LightingChunk lit) {
        PacketWriting.writeFramedPacket(buffer, ChunkDataPacket.SERIALIZER, 0x2D, packet, 256);
    }

    @SuppressWarnings("unused")
    static int single(Palette palette) {
        return palette.singleValue();
    }
}
