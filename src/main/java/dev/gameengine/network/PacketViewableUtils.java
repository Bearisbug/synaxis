package dev.gameengine.network;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import dev.gameengine.ServerProcess;
import dev.gameengine.Viewable;
import dev.gameengine.entity.Entity;
import dev.gameengine.entity.Player;
import dev.gameengine.network.ConnectionState;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.packet.PacketWriting;
import dev.gameengine.network.packet.server.BufferedPacket;
import dev.gameengine.network.packet.server.ServerPacket;
import dev.gameengine.network.player.PlayerConnection;
import dev.gameengine.network.player.PlayerSocketConnection;
import dev.gameengine.utils.ObjectPool;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * Buffers the packets sent to the viewers of a {@link Viewable} until the end of the tick.
 * <p>
 * One instance per {@link ServerProcess}, reachable with {@link ServerProcess#viewablePackets()},
 * so that ticking a process never flushes the packets another process is still filling.
 */
@ApiStatus.Internal
public final class PacketViewableUtils {
    // Weak keys let a storage disappear with its chunk, but WeakHashMap expunges stale entries
    // on read as well as on write, so every access has to go through the mutex.
    private final Map<Viewable, ViewableStorage> storageMap =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final ServerProcess process;
    private final boolean viewablePacket;
    private final ObjectPool<NetworkBuffer> pool;

    public PacketViewableUtils(ServerProcess process, boolean viewablePacket, int pooledBufferSize) {
        this.process = process;
        this.viewablePacket = viewablePacket;
        this.pool = ObjectPool.pool(
                () -> NetworkBuffer.resizableBuffer(pooledBufferSize, null),
                NetworkBuffer::clear);
    }

    public void prepareViewablePacket(Viewable viewable, ServerPacket serverPacket, @Nullable Entity entity) {
        if (entity != null && !entity.hasPredictableViewers()) {
            // Operation cannot be optimized
            entity.sendPacketToViewers(serverPacket);
            return;
        }
        if (!viewablePacket) {
            PacketSendingUtils.sendGroupedPacket(viewable.getViewers(), serverPacket, value -> !Objects.equals(value, entity));
            return;
        }
        final Player exception = entity instanceof Player player ? player : null;
        ViewableStorage storage = retrieveStorage(viewable);
        storage.append(serverPacket, exception);
    }

    private ViewableStorage retrieveStorage(Viewable viewable) {
        return storageMap.computeIfAbsent(viewable, _ -> new ViewableStorage());
    }

    public void flush() {
        if (!viewablePacket) return;
        // Snapshot under the mutex, the parallel traversal must not touch the map itself
        final List<Map.Entry<Viewable, ViewableStorage>> entries = new ArrayList<>();
        storageMap.forEach((viewable, storage) -> entries.add(Map.entry(viewable, storage)));
        entries.parallelStream().forEach(entry -> entry.getValue().process(entry.getKey()));
    }

    public void prepareViewablePacket(Viewable viewable, ServerPacket serverPacket) {
        prepareViewablePacket(viewable, serverPacket, null);
    }

    private final class ViewableStorage {
        // Player id -> list of offsets to ignore (32:32 bits)
        private final Int2ObjectMap<LongArrayList> entityIdMap = new Int2ObjectOpenHashMap<>();
        private final NetworkBuffer buffer = pool.getAndRegister(this);

        private synchronized void append(ServerPacket serverPacket, @Nullable Player exception) {
            final long start = buffer.writeIndex();
            // Viewable storage is only used for play packets, so fine to assume this.
            buffer.registries(process.registries());
            PacketWriting.writeFramedPacket(buffer, ConnectionState.PLAY, serverPacket, process.compressionThreshold());
            final long end = buffer.writeIndex();
            if (exception != null) {
                final long offsets = (start << 32) | (end & 0xFFFFFFFFL);
                LongList list = entityIdMap.computeIfAbsent(exception.getEntityId(), _ -> new LongArrayList());
                list.add(offsets);
            }
        }

        private synchronized void process(Viewable viewable) {
            if (buffer.writeIndex() == 0) return;
            NetworkBuffer copy = buffer.copy(0, buffer.writeIndex()).readOnly();
            viewable.getViewers().forEach(player -> processPlayer(player, copy));
            this.buffer.clear();
            this.entityIdMap.clear();
        }

        private void processPlayer(Player player, NetworkBuffer buffer) {
            final long capacity = buffer.capacity();
            final PlayerConnection connection = player.getPlayerConnection();
            final LongArrayList pairs = entityIdMap.get(player.getEntityId());
            if (pairs == null) {
                // No range exception, write the whole buffer
                writeTo(connection, buffer, 0, capacity);
                return;
            }
            // Player has range exception(s)
            // Ensure that we skip the specified parts of the buffer
            int lastWrite = 0;
            final long[] elements = pairs.elements();
            for (int i = 0; i < pairs.size(); ++i) {
                final long offsets = elements[i];
                final int start = (int) (offsets >> 32);
                if (start != lastWrite) writeTo(connection, buffer, lastWrite, start - lastWrite);
                lastWrite = (int) offsets; // End = last 32 bits
            }
            if (capacity != lastWrite) writeTo(connection, buffer, lastWrite, capacity - lastWrite);
        }

        private static void writeTo(PlayerConnection connection, NetworkBuffer buffer, long offset, long length) {
            if (connection instanceof PlayerSocketConnection socketConnection) {
                socketConnection.sendPacket(new BufferedPacket(buffer, offset, length));
                return;
            }
            // TODO for non-socket connection
        }
    }
}
