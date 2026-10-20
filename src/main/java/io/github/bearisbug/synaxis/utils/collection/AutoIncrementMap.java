package io.github.bearisbug.synaxis.utils.collection;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class AutoIncrementMap<K> {
    private final Object2IntOpenHashMap<K> write = new Object2IntOpenHashMap<>();
    // Read without holding the lock, so the table it points at has to be published safely
    private volatile Object2IntOpenHashMap<K> read;
    private int lastIndex;

    public AutoIncrementMap() {
        this.write.defaultReturnValue(-1);
        this.read = write.clone();
    }

    /**
     * Gets the index of a key, giving a key seen for the first time the next index. The index stays taken for the life
     * of the map, and each new key copies the whole table and publishes the copy; a known key is read from the
     * volatile copy without locking.
     *
     * @param key the key
     * @return the index of the key
     */
    public int get(K key) {
        int index = read.getInt(key);
        if (index == -1) {
            synchronized (write) {
                var write = this.write;
                index = write.getInt(key);
                if (index == -1) {
                    index = lastIndex++;
                    write.put(key, index);
                    read = write.clone();
                }
            }
        }
        return index;
    }
}
