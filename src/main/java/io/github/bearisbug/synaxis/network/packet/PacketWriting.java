package io.github.bearisbug.synaxis.network.packet;

import io.github.bearisbug.synaxis.JvmFlags;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import org.jctools.queues.MessagePassingQueue;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.ApiStatus;

import java.util.function.BiPredicate;

/**
 * Tools to write packets into a {@link NetworkBuffer} for network processing.
 * <p>
 * Fairly internal and performance sensitive.
 */
@ApiStatus.Internal
public final class PacketWriting {
    /** The width of the {@link NetworkBuffer#VAR_INT_3} length fields of a frame header. */
    private static final int VAR_INT_3_SIZE = 3;

    public static void writeFramedPacket(NetworkBuffer buffer,
                                         ConnectionState state,
                                         ClientPacket packet,
                                         int compressionThreshold) throws IndexOutOfBoundsException {
        final PacketRegistry.PacketInfo<? super ClientPacket> packetInfo =
                packetInfo(PacketVanilla.CLIENT_PACKET_PARSER, state, packet);
        writeFramedPacket(buffer, packetInfo.serializer(), packetInfo.id(), packet, compressionThreshold);
    }

    public static void writeFramedPacket(NetworkBuffer buffer,
                                         ConnectionState state,
                                         ServerPacket packet,
                                         int compressionThreshold) throws IndexOutOfBoundsException {
        final PacketRegistry.PacketInfo<? super ServerPacket> packetInfo =
                packetInfo(PacketVanilla.SERVER_PACKET_PARSER, state, packet);
        writeFramedPacket(buffer, packetInfo.serializer(), packetInfo.id(), packet, compressionThreshold);
    }

    /** Looks the packet up in the registry of the state, before anything is written. */
    private static <T> PacketRegistry.PacketInfo<? super T> packetInfo(PacketParser<? super T> parser,
                                                                      ConnectionState state, T packet) {
        @SuppressWarnings("unchecked") // We assume ConnectionState and PacketRegistry are in sync
        final PacketRegistry<? super T> registry = (PacketRegistry<? super T>) parser.stateRegistry(state);
        return registry.packetInfo(packet);
    }

    public static <T> void writeFramedPacket(NetworkBuffer buffer,
                                             NetworkBuffer.Type<? super T> type,
                                             int id, T packet,
                                             int compressionThreshold) throws IndexOutOfBoundsException {
        if (compressionThreshold <= 0) writeUncompressedFormat(buffer, type, id, packet);
        else writeCompressedFormat(buffer, type, id, packet, compressionThreshold);
    }

    private static <T> void writeUncompressedFormat(NetworkBuffer buffer,
                                                    NetworkBuffer.Type<? super T> type,
                                                    int id, T packet) throws IndexOutOfBoundsException {
        // Uncompressed format https://minecraft.wiki/w/Minecraft_Wiki:Projects/wiki.vg_merge/Protocol#Without_compression
        final long lengthIndex = buffer.advanceWrite(VAR_INT_3_SIZE);
        buffer.write(NetworkBuffer.VAR_INT, id);
        buffer.write(type, packet);
        final long finalSize = buffer.writeIndex() - (lengthIndex + VAR_INT_3_SIZE);
        buffer.writeAt(lengthIndex, NetworkBuffer.VAR_INT_3, (int) finalSize);
    }

    private static <T> void writeCompressedFormat(NetworkBuffer buffer,
                                                  NetworkBuffer.Type<? super T> type,
                                                  int id, T packet,
                                                  int compressionThreshold) throws IndexOutOfBoundsException {
        // Compressed format https://minecraft.wiki/w/Minecraft_Wiki:Projects/wiki.vg_merge/Protocol#With_compression
        final long compressedIndex = buffer.advanceWrite(VAR_INT_3_SIZE);
        final long uncompressedIndex = buffer.advanceWrite(VAR_INT_3_SIZE);
        final long contentStart = buffer.writeIndex();
        buffer.write(NetworkBuffer.VAR_INT, id);
        buffer.write(type, packet);
        final long packetSize = buffer.writeIndex() - contentStart;
        final boolean compressed = packetSize >= compressionThreshold;
        if (compressed) {
            // Write the compressed content into the pooled buffer
            // and compress it into the current buffer
            NetworkBuffer input = PacketVanilla.PACKET_POOL.get();
            try {
                if (input.capacity() < packetSize) input.resize(packetSize);
                NetworkBuffer.copy(buffer, contentStart, input, 0, packetSize);
                buffer.writeIndex(contentStart);
                input.compress(0, packetSize, buffer);
            } finally {
                PacketVanilla.PACKET_POOL.add(input);
            }
        }
        // Packet header (Packet + Data Length)
        buffer.writeAt(compressedIndex, NetworkBuffer.VAR_INT_3, (int) (buffer.writeIndex() - uncompressedIndex));
        buffer.writeAt(uncompressedIndex, NetworkBuffer.VAR_INT_3, compressed ? (int) packetSize : 0);
    }

    public static NetworkBuffer allocateTrimmedPacket(ConnectionState state,
                                                      ClientPacket packet,
                                                      @Nullable Registries registries,
                                                      int compressionThreshold) {
        return allocateTrimmedPacket(PacketVanilla.CLIENT_PACKET_PARSER, state, packet, registries, compressionThreshold);
    }

    public static NetworkBuffer allocateTrimmedPacket(ConnectionState state,
                                                      ServerPacket packet,
                                                      @Nullable Registries registries,
                                                      int compressionThreshold) {
        return allocateTrimmedPacket(PacketVanilla.SERVER_PACKET_PARSER, state, packet, registries, compressionThreshold);
    }

    private static <T> NetworkBuffer allocateTrimmedPacket(
            PacketParser<T> parser,
            ConnectionState state,
            T packet,
            @Nullable Registries registries,
            int compressionThreshold) {
        NetworkBuffer buffer = PacketVanilla.PACKET_POOL.get();
        buffer.registries(registries);
        try {
            return allocateTrimmedPacket(buffer, parser, state, packet, compressionThreshold);
        } finally {
            PacketVanilla.PACKET_POOL.add(buffer);
        }
    }

    public static <T> NetworkBuffer allocateTrimmedPacket(
            NetworkBuffer tmpBuffer,
            PacketParser<? super T> parser,
            ConnectionState state,
            T packet,
            int compressionThreshold) {
        final PacketRegistry.PacketInfo<? super T> packetInfo = packetInfo(parser, state, packet);
        final int id = packetInfo.id();
        final NetworkBuffer.Type<? super T> serializer = packetInfo.serializer();
        try {
            writeFramedPacket(tmpBuffer, serializer, id, packet, compressionThreshold);
            return tmpBuffer.copy(0, tmpBuffer.writeIndex());
        } catch (IndexOutOfBoundsException _) {
            final long sizeOf = serializer.sizeOf(packet, tmpBuffer.registries());
            if (sizeOf > JvmFlags.MAX_PACKET_SIZE) {
                throw new IllegalStateException("Packet too large: " + sizeOf + " > " + JvmFlags.MAX_PACKET_SIZE
                        + " (synaxis.max-packet-size)");
            }
            // 15 bytes on top of the compressed size of the payload: the two VAR_INT_3 lengths of the
            // header (6), a packet id of up to 5 bytes, and 4 bytes of slack. The packet id goes into
            // the compressed input too, the slack covers what it adds to the shifted terms. The write
            // below catches no IndexOutOfBoundsException, so the slack must not shrink.
            tmpBuffer.resize(zlibCompressBound(sizeOf) + 15);
            tmpBuffer.writeIndex(0);
            writeFramedPacket(tmpBuffer, serializer, id, packet, compressionThreshold);
            return tmpBuffer.copy(0, tmpBuffer.writeIndex());
        }
    }

    /**
     * zlib's {@code compressBound()}: the most deflate makes of {@code n} bytes, incompressible
     * input grows.
     */
    private static long zlibCompressBound(long n) {
        return n + (n >> 12) + (n >> 14) + (n >> 25) + 13;
    }

    /**
     * Writes packets off the head of the queue into the buffer, at least {@code minWrite} of them if
     * the queue holds that many: the buffer is grown, up to the maximum packet size, until they fit.
     * <p>
     * The writer decides what happens to the packet it is handed:
     * <ul>
     * <li>{@code true}: the packet is done with and leaves the queue, written or deliberately
     * dropped (cancelled by a listener, movement shed from an outbound backlog). {@code minWrite}
     * counts these.</li>
     * <li>{@code false}, or an {@link IndexOutOfBoundsException}: the packet did not fit. The buffer
     * goes back to the write index it had before the call and the packet stays at the head of the
     * queue, to be handed to the writer again, which must therefore repeat no side effect of an
     * earlier attempt.</li>
     * <li>any other {@link RuntimeException}: the packet leaves the queue, the buffer goes back to
     * the write index it had before the call, and the exception is rethrown.</li>
     * </ul>
     *
     * @throws IllegalStateException if a packet does not fit in a buffer of the maximum packet size,
     *                               the packet leaves the queue
     */
    public static <T> void writeQueue(NetworkBuffer buffer, MessagePassingQueue<T> queue, int minWrite,
                                      BiPredicate<NetworkBuffer, T> writer) {
        // The goal of this method is to write at the very least `minWrite` packets if the queue permits it.
        // The buffer is resized if it cannot hold this minimum.
        // A packet that does not fit stays in the queue and is handed to the writer again, which must
        // therefore repeat no side effect of a previous attempt.
        final int size = queue.size();
        minWrite = Math.min(minWrite, size);
        T packet;
        int written = 0;
        while ((packet = queue.peek()) != null) {
            final long index = buffer.writeIndex();
            boolean consumed;
            try {
                consumed = writer.test(buffer, packet);
            } catch (IndexOutOfBoundsException _) {
                consumed = false;
            } catch (RuntimeException e) {
                // A packet that fails to be written fails again: taken off the queue before the
                // failure goes up, so that nothing runs it again. What it wrote is cut off, the
                // buffer keeps only the packets before it
                queue.poll();
                buffer.writeIndex(index);
                throw e;
            }
            // A writer may take a packet off the queue without writing it: cancelled by a listener, or
            // movement shed from an outbound backlog
            assert !consumed || buffer.writeIndex() >= index;
            if (consumed) {
                queue.poll();
                written++;
            } else {
                buffer.writeIndex(index);
                if (written < minWrite) {
                    // Try again with a bigger buffer
                    final long newSize = Math.min(buffer.capacity() * 2, JvmFlags.MAX_PACKET_SIZE);
                    if (newSize == buffer.capacity()) {
                        // The buffer is at the maximum packet size, retrying would spin the write
                        // thread forever on a packet that can never be written.
                        queue.poll();
                        throw new IllegalStateException("Packet too large to be written: "
                                + packet.getClass().getSimpleName() + " > " + buffer.capacity() + " (synaxis.max-packet-size)");
                    }
                    buffer.resize(newSize);
                } else {
                    // At least one packet has been written
                    // Not worth resizing to fit more, we'll try again next flush
                    break;
                }
            }
        }
    }
}
