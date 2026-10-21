package io.github.bearisbug.synaxis.instance;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;

import java.util.HashSet;
import java.util.Set;

/**
 * The chunk tickets of one instance ({@code docs/DESIGN-M3.md} {@code API-M3-006}) and which of its
 * loaded chunks have none, since when. A ticket only keeps a chunk from the automatic unload, it
 * never loads one; tickets live on coordinates, loaded or not.
 * <p>
 * One lock of its own guards everything, never the instance monitor: players change their view
 * tickets on their tick threads (the ring an instance change preloads also on the thread that
 * finishes the change), plugins theirs on any thread, the unload reads them on the tick scheduler
 * thread. Nothing else is locked while it is held.
 */
final class ChunkTickets {
    private final Object lock = new Object();

    // Everything below is guarded by lock

    /** Counts the ticks of the instance: the idle ticks of a chunk are measured on it. */
    private long clock;
    /** How many chunk rings of players cover a chunk, spawn rings still loading included. */
    private final Long2IntOpenHashMap viewTickets = new Long2IntOpenHashMap();
    /** The owners of the plugin tickets of a chunk, one ticket per owner. */
    private final Long2ObjectOpenHashMap<Set<Object>> pluginTickets = new Long2ObjectOpenHashMap<>();
    /** The chunks of the instance as they were loaded, so that a late unload of one leaves its reload alone. */
    private final Long2ObjectOpenHashMap<Chunk> loaded = new Long2ObjectOpenHashMap<>();
    /**
     * The loaded chunks without a ticket and the tick their last ticket went, or they were loaded
     * without one. In the order they became idle, which is the order of the ticks, since the clock
     * only moves under the lock.
     */
    private final Long2LongLinkedOpenHashMap idleSince = new Long2LongLinkedOpenHashMap();

    /** A tick of the instance starts. */
    void tick() {
        synchronized (lock) {
            clock++;
        }
    }

    void loaded(long index, Chunk chunk) {
        synchronized (lock) {
            loaded.put(index, chunk);
            if (count(index) == 0) becameIdle(index);
        }
    }

    void unloaded(long index, Chunk chunk) {
        synchronized (lock) {
            if (loaded.get(index) != chunk) return;
            loaded.remove(index);
            idleSince.remove(index);
        }
    }

    boolean addPluginTicket(long index, Object owner) {
        synchronized (lock) {
            final Set<Object> owners = pluginTickets.computeIfAbsent(index, _ -> new HashSet<>());
            if (!owners.add(owner)) return false;
            if (count(index) == 1) idleSince.remove(index);
            return true;
        }
    }

    boolean removePluginTicket(long index, Object owner) {
        synchronized (lock) {
            final Set<Object> owners = pluginTickets.get(index);
            if (owners == null || !owners.remove(owner)) return false;
            if (owners.isEmpty()) pluginTickets.remove(index);
            if (count(index) == 0) becameIdle(index);
            return true;
        }
    }

    void addViewTickets(long[] chunks) {
        synchronized (lock) {
            for (long index : chunks) {
                viewTickets.addTo(index, 1);
                if (count(index) == 1) idleSince.remove(index);
            }
        }
    }

    void removeViewTickets(long[] chunks) {
        synchronized (lock) {
            for (long index : chunks) {
                final int tickets = viewTickets.get(index);
                // Only the test hook below leaves a ring without its ticket
                if (tickets <= 0) continue;
                if (tickets == 1) viewTickets.remove(index);
                else viewTickets.put(index, tickets - 1);
                if (count(index) == 0) becameIdle(index);
            }
        }
    }

    /** The test hook of {@link Instance#clearViewTickets(int, int)}. */
    void clearViewTickets(long index) {
        synchronized (lock) {
            if (viewTickets.remove(index) > 0 && count(index) == 0) becameIdle(index);
        }
    }

    int ticketCount(long index) {
        synchronized (lock) {
            return count(index);
        }
    }

    /**
     * Returns at most {@code limit} of the loaded chunks that have been without a ticket for at least
     * {@code grace} ticks, the longest idle first, after the first {@code skip} of them. The lock is
     * held for {@code skip + limit} entries at most, however many chunks are idle.
     */
    LongList idleFor(int grace, int skip, int limit) {
        final LongArrayList idle = new LongArrayList();
        synchronized (lock) {
            int skipped = 0;
            for (var entry : idleSince.long2LongEntrySet()) {
                if (clock - entry.getLongValue() < grace || idle.size() == limit) break;
                if (skipped < skip) {
                    skipped++;
                    continue;
                }
                idle.add(entry.getLongKey());
            }
        }
        return idle;
    }

    /** Whether {@code chunk} is still loaded at {@code index} without a ticket, which the unload checks again under the lock. */
    boolean stillIdle(long index, Chunk chunk) {
        synchronized (lock) {
            return loaded.get(index) == chunk && count(index) == 0;
        }
    }

    private int count(long index) {
        final Set<Object> owners = pluginTickets.get(index);
        return viewTickets.get(index) + (owners == null ? 0 : owners.size());
    }

    private void becameIdle(long index) {
        if (loaded.containsKey(index)) idleSince.putAndMoveToLast(index, clock);
    }
}
