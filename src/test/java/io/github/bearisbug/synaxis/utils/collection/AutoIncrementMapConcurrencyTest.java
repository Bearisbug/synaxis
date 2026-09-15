package io.github.bearisbug.synaxis.utils.collection;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AutoIncrementMapConcurrencyTest {

    private static final int THREADS = 4;
    private static final int KEYS = 500;

    /**
     * The read table is a mutable map whose fields are not final, published from inside the lock and
     * then read without any synchronization. Only a volatile write makes that publication safe; a
     * plain field lets a reader observe the new reference before the entries it was cloned with,
     * which silently hands out the index of another key.
     */
    @Test
    public void readTableIsPublishedSafely() throws NoSuchFieldException {
        final Field read = AutoIncrementMap.class.getDeclaredField("read");
        assertTrue(Modifier.isVolatile(read.getModifiers()),
                "AutoIncrementMap.read is read outside the lock and must be volatile");
    }

    @Test
    public void concurrentKeysGetDistinctIndexes() throws InterruptedException, ExecutionException {
        final AutoIncrementMap<String> map = new AutoIncrementMap<>();
        final ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        final List<Callable<Map<String, Integer>>> tasks = new ArrayList<>(THREADS);
        for (int thread = 0; thread < THREADS; thread++) {
            final int offset = thread * KEYS;
            tasks.add(() -> {
                final Map<String, Integer> indexes = new HashMap<>(KEYS);
                for (int i = 0; i < KEYS; i++) {
                    final String key = "key" + (offset + i);
                    indexes.put(key, map.get(key));
                }
                return indexes;
            });
        }

        try {
            final Set<Integer> seen = new HashSet<>();
            for (Future<Map<String, Integer>> future : executor.invokeAll(tasks)) {
                for (Map.Entry<String, Integer> entry : future.get().entrySet()) {
                    assertTrue(seen.add(entry.getValue()),
                            "Index " + entry.getValue() + " was handed out twice");
                    assertEquals(entry.getValue().intValue(), map.get(entry.getKey()),
                            "Index of " + entry.getKey() + " changed after publication");
                }
            }
            assertEquals(THREADS * KEYS, seen.size());
        } finally {
            executor.shutdownNow();
        }
    }
}
