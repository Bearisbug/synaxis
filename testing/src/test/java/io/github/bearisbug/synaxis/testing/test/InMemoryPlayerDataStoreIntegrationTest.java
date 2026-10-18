package io.github.bearisbug.synaxis.testing.test;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.plugin.DataSection;
import io.github.bearisbug.synaxis.plugin.SaveResult;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.InMemoryPlayerDataStore;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stand-in store a plugin tests against ({@code docs/DESIGN-PROD.md} {@code API-PROD-005}).
 */
@EnvTest
public class InMemoryPlayerDataStoreIntegrationTest {

    private static final DataSection<AtomicInteger> COINS = new DataSection<>() {
        @Override
        public String id() {
            return "probe:coins";
        }

        @Override
        public int schemaVersion() {
            return 1;
        }

        @Override
        public AtomicInteger createDefault() {
            return new AtomicInteger();
        }

        @Override
        public AtomicInteger decode(JsonElement json, int storedVersion) {
            return new AtomicInteger(json.getAsInt());
        }

        @Override
        public JsonElement encode(AtomicInteger value) {
            return new JsonPrimitive(value.get());
        }
    };

    @Test
    public void sectionsStartAsTheDefaultAndSavesAndCommitsSucceed(Env env) {
        final InMemoryPlayerDataStore store = new InMemoryPlayerDataStore();
        store.register(COINS);
        final Player player = env.createPlayer(env.createFlatInstance(), new Pos(0, 42, 0));

        assertEquals(0, store.get(player, COINS).get());
        store.get(player, COINS).set(4);
        store.markDirty(player, COINS);
        assertEquals(Set.of("probe:coins"), store.dirty(player));
        assertEquals(SaveResult.SAVED, store.saveNow(player).join());
        assertEquals(Set.of(), store.dirty(player));

        final AtomicInteger applied = new AtomicInteger();
        assertEquals(SaveResult.SAVED, store.commitTogether(
                Map.of(player, Map.<DataSection<?>, Object>of(COINS, new AtomicInteger(9))), applied::incrementAndGet).join());
        assertEquals(9, store.get(player, COINS).get());
        assertEquals(1, applied.get());
    }

    @Test
    public void aFrozenPlayerCommitsNothingAndRegistrationIsOnce(Env env) {
        final InMemoryPlayerDataStore store = new InMemoryPlayerDataStore();
        store.register(COINS);
        assertThrows(IllegalStateException.class, () -> store.register(COINS));
        final Player player = env.createPlayer(env.createFlatInstance(), new Pos(0, 42, 0));

        store.freeze(player, true);
        final AtomicInteger applied = new AtomicInteger();
        assertEquals(SaveResult.UNAVAILABLE, store.commitTogether(
                Map.of(player, Map.<DataSection<?>, Object>of(COINS, new AtomicInteger(1))), applied::incrementAndGet).join());
        assertEquals(SaveResult.UNAVAILABLE, store.saveNow(player).join());
        assertEquals(0, applied.get());
        assertEquals(0, store.get(player, COINS).get());
    }

    @Test
    public void aCommitOfAValueTheHostCouldNotWriteIsRefusedAsTheHostRefusesIt(Env env) {
        final InMemoryPlayerDataStore store = new InMemoryPlayerDataStore();
        store.register(COINS);
        final Player player = env.createPlayer(env.createFlatInstance(), new Pos(0, 42, 0));
        final AtomicInteger applied = new AtomicInteger();
        final Map<DataSection<?>, Object> nothing = new HashMap<>();
        nothing.put(COINS, null);

        assertThrows(IllegalArgumentException.class, () -> store.commitTogether(Map.of(player, nothing), applied::incrementAndGet));
        final IllegalArgumentException typed = assertThrows(IllegalArgumentException.class, () -> store.commitTogether(
                Map.of(player, Map.<DataSection<?>, Object>of(COINS, "nine")), applied::incrementAndGet));
        assertTrue(typed.getMessage().contains("probe:coins"), typed.getMessage());
        assertEquals(0, applied.get());
        assertEquals(0, store.get(player, COINS).get());
    }
}
