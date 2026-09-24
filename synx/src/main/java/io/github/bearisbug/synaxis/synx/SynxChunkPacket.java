package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.instance.palette.Palette;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.data.ChunkData;
import io.github.bearisbug.synaxis.registry.Registries;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Segment ① of API-M3-001: a {@code ChunkDataPacket} framed the way a compressed connection sends
 * it, frame length VarInt, uncompressed length VarInt, zlib data. A shard writes these bytes to the
 * socket unchanged, so the frame follows the protocol, not this file format.
 * <p>
 * The body is canonical: every section palette is rebuilt from its content before it is written,
 * so the bytes depend on what the chunk holds and not on the order it was written in or the
 * palette form it arrived in ({@code REQ-M3-003}).
 */
public final class SynxChunkPacket {
    private SynxChunkPacket() {
    }

    /**
     * Frames the canonical form of {@code packet}.
     *
     * @throws IllegalArgumentException if the packet does not hold {@link SynxSettings#sectionCount()} sections
     */
    public static byte[] encode(ChunkDataPacket packet, SynxSettings settings, Registries registries) {
        final byte[] body = body(canonical(packet, settings, registries), registries);
        final ByteArrayOutputStream frame = new ByteArrayOutputStream(body.length / 2 + 16);
        if (body.length < settings.compressionThreshold()) {
            writeVarInt(frame, 1 + body.length);
            writeVarInt(frame, 0);
            frame.writeBytes(body);
        } else {
            final byte[] compressed = deflate(body, settings.compressionLevel());
            writeVarInt(frame, varIntSize(body.length) + compressed.length);
            writeVarInt(frame, body.length);
            frame.writeBytes(compressed);
        }
        return frame.toByteArray();
    }

    /**
     * The uncompressed packet body of a framed segment ①: packet id VarInt followed by the packet.
     * This is what {@code region_content_hash} is computed over.
     *
     * @throws IllegalArgumentException if the frame is cut short, too long, or not a zlib stream of the stated length
     */
    public static byte[] body(MemorySegment framed) {
        final long[] position = {0};
        final int frameLength = readVarInt(framed, position);
        if (frameLength != framed.byteSize() - position[0]) {
            throw new IllegalArgumentException("frame length " + frameLength + ", "
                    + (framed.byteSize() - position[0]) + " bytes follow");
        }
        final int dataLength = readVarInt(framed, position);
        final MemorySegment data = framed.asSlice(position[0]);
        if (dataLength == 0) return data.toArray(ValueLayout.JAVA_BYTE);
        return inflate(data.asByteBuffer(), dataLength);
    }

    public static byte[] body(byte[] framed) {
        return body(MemorySegment.ofArray(framed));
    }

    /**
     * Reads a framed segment ① back into a packet. Block entities come back through
     * {@code ChunkData}, which drops the types that have no block of the same name; the complete
     * set is in segment ③.
     */
    public static ChunkDataPacket decode(byte[] framed, Registries registries) {
        final byte[] body = body(framed);
        final NetworkBuffer buffer = NetworkBuffer.wrap(body, 0, body.length, registries);
        final int id = buffer.read(NetworkBuffer.VAR_INT);
        if (id != SynxFormat.CHUNK_DATA_PACKET_ID) {
            throw new IllegalArgumentException("packet id " + id + " is not ChunkDataPacket");
        }
        final ChunkDataPacket packet = buffer.read(ChunkDataPacket.SERIALIZER);
        if (buffer.readableBytes() != 0) {
            throw new IllegalArgumentException(buffer.readableBytes() + " bytes after the packet");
        }
        return packet;
    }

    private static ChunkDataPacket canonical(ChunkDataPacket packet, SynxSettings settings, Registries registries) {
        final NetworkBuffer.Type<ChunkData.Section> type = ChunkData.Section.networkType(registries.biome().size());
        final byte[] data = packet.chunkData().data();
        final NetworkBuffer input = NetworkBuffer.wrap(data, 0, data.length, registries);
        final List<ChunkData.Section> sections = new ArrayList<>(settings.sectionCount());
        while (input.readableBytes() > 0) {
            final ChunkData.Section section = input.read(type);
            sections.add(new ChunkData.Section(section.blockCount(), section.liquidCount(),
                    canonical(section.blockStates(), Palette.blocks()), canonical(section.biomes(), Palette.biomes())));
        }
        if (sections.size() != settings.sectionCount()) {
            throw new IllegalArgumentException("chunk " + packet.chunkX() + "," + packet.chunkZ() + " has "
                    + sections.size() + " sections, the world has " + settings.sectionCount());
        }
        final byte[] canonicalData = NetworkBuffer.makeArray(buffer -> {
            for (ChunkData.Section section : sections) buffer.write(type, section);
        }, registries);
        final ChunkData chunkData = packet.chunkData();
        return new ChunkDataPacket(packet.chunkX(), packet.chunkZ(),
                new ChunkData(chunkData.heightmaps(), canonicalData, chunkData.blockEntities()), packet.lightData());
    }

    /**
     * Copies {@code palette} into an empty one of the same kind, then {@code optimize(SIZE)}. The copy
     * is what makes the order canonical: {@code optimize} alone keeps an indirect palette that is
     * already at its narrowest width in the order it was written, entries no longer used included.
     * A section with more distinct values than the widest indirect palette holds stays direct.
     */
    private static Palette canonical(Palette palette, Palette empty) {
        empty.setAll(palette::get);
        empty.optimize(Palette.Optimization.SIZE);
        return empty;
    }

    private static byte[] body(ChunkDataPacket packet, Registries registries) {
        return NetworkBuffer.makeArray(buffer -> {
            buffer.write(NetworkBuffer.VAR_INT, SynxFormat.CHUNK_DATA_PACKET_ID);
            buffer.write(ChunkDataPacket.SERIALIZER, packet);
        }, registries);
    }

    static byte[] deflate(byte[] input, int level) {
        final Deflater deflater = new Deflater(level);
        try {
            deflater.setInput(input);
            deflater.finish();
            final ByteArrayOutputStream output = new ByteArrayOutputStream(input.length / 2 + 64);
            final byte[] chunk = new byte[8192];
            while (!deflater.finished()) output.write(chunk, 0, deflater.deflate(chunk));
            return output.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /**
     * @throws IllegalArgumentException unless {@code input} is one zlib stream of exactly {@code length} bytes
     */
    static byte[] inflate(ByteBuffer input, int length) {
        final Inflater inflater = new Inflater();
        try {
            inflater.setInput(input);
            final byte[] output = new byte[length];
            int written = 0;
            while (written < length) {
                final int n = inflater.inflate(output, written, length - written);
                if (n == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break;
                written += n;
            }
            if (written != length || !inflater.finished() || inflater.getRemaining() != 0) {
                throw new IllegalArgumentException("zlib stream does not hold exactly " + length + " bytes");
            }
            return output;
        } catch (DataFormatException e) {
            throw new IllegalArgumentException("not a zlib stream", e);
        } finally {
            inflater.end();
        }
    }

    static void writeVarInt(ByteArrayOutputStream output, int value) {
        while ((value & ~0x7F) != 0) {
            output.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        output.write(value);
    }

    static int varIntSize(int value) {
        int size = 1;
        while ((value & ~0x7F) != 0) {
            size++;
            value >>>= 7;
        }
        return size;
    }

    private static int readVarInt(MemorySegment segment, long[] position) {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            if (position[0] >= segment.byteSize()) throw new IllegalArgumentException("VarInt cut short");
            final byte b = segment.get(ValueLayout.JAVA_BYTE, position[0]++);
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
        }
        throw new IllegalArgumentException("VarInt longer than 5 bytes at " + position[0]);
    }
}
