package io.github.bearisbug.synaxis.app;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerSpawnEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.plugin.Admission;
import io.github.bearisbug.synaxis.plugin.AdmissionProvider;
import io.github.bearisbug.synaxis.plugin.DataSection;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.PluginContext;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;
import io.github.bearisbug.synaxis.plugin.SaveResult;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;
import io.github.bearisbug.synaxis.app.store.MemoryBackend;
import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.app.store.RedisLink;
import io.github.bearisbug.synaxis.app.store.StoreBackend;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The player store as a plugin and a connection see it ({@code docs/DESIGN-PROD.md}
 * {@code REQ-PROD-006}, {@code API-PROD-005}, §10 row level visibility, §12.1): sections only in the
 * plugin's own namespace and only in {@code onEnable}, the load between the handshake and the
 * admission, and what a load or an admission that fails leaves behind.
 */
@EnvTest
class PlayerDataIntegrationTest {

    @TempDir
    Path workingDirectory;

    private @Nullable EventLedger ledger;

    @AfterEach
    void closeLedger() {
        if (ledger != null) ledger.close();
    }

    /** A counter section, {@code <plugin_id>:<name>} as the test wants it. */
    private record Counter(String id) implements DataSection<AtomicInteger> {
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
    }

    /** A plugin that does in onEnable what the test hands it. */
    private static final class Probe implements SynaxisPlugin {
        private final PluginDescriptor descriptor;
        private final Consumer<PluginContext> onEnable;
        private @Nullable PluginContext context;

        Probe(String id, Consumer<PluginContext> onEnable) {
            this.descriptor = new PluginDescriptor(id, "1.0.0", PluginApi.API_VERSION);
            this.onEnable = onEnable;
        }

        @Override
        public PluginDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public void onEnable(PluginContext ctx) {
            this.context = ctx;
            onEnable.accept(ctx);
        }

        @Override
        public void onDisable() {
        }
    }

    private PluginHost host(Env env, StoreBackend backend) {
        ledger = new EventLedger(workingDirectory.resolve("logs"), "test", EventLedger.DEFAULT_MAX_BYTES_PER_DAY);
        ledger.start();
        final PlayerStore store = new PlayerStore(env.process(), backend, RedisLink.disabled("test"), "test", false,
                PlayerStore.Options.DEFAULT, ledger::record);
        return new PluginHost(env.process(), workingDirectory.resolve("plugins"), ledger, store);
    }

    @Test
    void aSectionIsTheLoadedValueInTheWorldAndSavesThroughTheStore(Env env) {
        final Counter coins = new Counter("probe:coins");
        final Instance match = env.createFlatInstance();
        final AtomicReference<Integer> atSpawn = new AtomicReference<>();
        final Probe probe = new Probe("probe", ctx -> {
            ctx.playerData().register(coins);
            ctx.admission().register(_ -> new Admission.Accept(match, 1));
            ctx.events().addListener(PlayerSpawnEvent.class, event ->
                    atSpawn.set(ctx.playerData().get(event.getPlayer(), coins).get()));
        });
        final PluginHost host = host(env, new MemoryBackend());
        host.enable(List.of(probe), Set.of());

        final Player player = env.createPlayer(match, new Pos(0, 41, 0));

        assertEquals(0, atSpawn.get(), "loaded before the player entered the world, as the default");
        final PluginContext ctx = Objects.requireNonNull(probe.context);
        ctx.playerData().get(player, coins).set(3);
        ctx.playerData().markDirty(player, coins);
        assertEquals(SaveResult.SAVED, ctx.playerData().saveNow(player).join());
        assertFalse(ctx.playerData().isFrozen(player));
        host.disableAll();
    }

    @Test
    void aPluginOnlyRegistersInItsNamespaceAndOnlyInOnEnable(Env env) {
        final List<Throwable> refused = new CopyOnWriteArrayList<>();
        final Probe probe = new Probe("mmo", ctx -> {
            ctx.playerData().register(new Counter("mmo:inventory"));
            for (String id : List.of("other:bag", "mmo:", "mmobag:x")) {
                try {
                    ctx.playerData().register(new Counter(id));
                } catch (IllegalStateException e) {
                    refused.add(e);
                }
            }
            try {
                ctx.playerData().register(new Counter("mmo:inventory"));
            } catch (IllegalStateException e) {
                refused.add(e);
            }
        });
        final PluginHost host = host(env, new MemoryBackend());
        host.enable(List.of(probe), Set.of());

        assertEquals(4, refused.size(), "another namespace, an empty name, a prefix lookalike, a second registration");
        final PluginContext ctx = Objects.requireNonNull(probe.context);
        assertThrows(IllegalStateException.class, () -> ctx.playerData().register(new Counter("mmo:late")),
                "after onEnable returned");
        host.disableAll();
    }

    @Test
    void aPluginCannotReadASectionItDidNotRegister(Env env) {
        final Counter theirs = new Counter("other:coins");
        final Instance match = env.createFlatInstance();
        final Probe other = new Probe("other", ctx -> ctx.playerData().register(theirs));
        final Probe probe = new Probe("probe", ctx -> ctx.admission().register(_ -> new Admission.Accept(match, 1)));
        final PluginHost host = host(env, new MemoryBackend());
        host.enable(List.of(other, probe), Set.of());
        final Player player = env.createPlayer(match, new Pos(0, 41, 0));

        final PluginContext ctx = Objects.requireNonNull(probe.context);
        assertThrows(IllegalStateException.class, () -> ctx.playerData().get(player, theirs));
        assertThrows(IllegalStateException.class, () -> ctx.playerData().markDirty(player, theirs));
        assertEquals(0, Objects.requireNonNull(other.context).playerData().get(player, theirs).get(),
                "its owner reads it");
        host.disableAll();
    }

    @Test
    void aLoadThatFailsTurnsTheConnectionAwayBeforeTheAdmission(Env env) throws IOException {
        final AtomicInteger admitted = new AtomicInteger();
        final Instance match = env.createFlatInstance();
        final AdmissionProvider provider = _ -> {
            admitted.incrementAndGet();
            return new Admission.Accept(match, 1);
        };
        final PluginHost host = host(env, new FailingBackend());
        host.enable(List.of(new Probe("probe", ctx -> ctx.admission().register(provider))), Set.of());

        final Player player = env.createPlayer(match, new Pos(0, 41, 0));

        assertFalse(player.getPlayerConnection().isOnline());
        assertEquals(0, admitted.get(), "no admission without the player's data");
        host.disableAll();
        final List<String> lines = readLedger();
        assertTrue(lines.stream().anyMatch(line -> line.contains("\"client_rejected\"")
                && line.contains("\"reason\":\"state-unavailable\"")), lines.toString());
    }

    @Test
    void aRefusedAdmissionGivesTheLeaseBack(Env env) {
        final CountingBackend backend = new CountingBackend();
        final Probe probe = new Probe("probe", ctx -> ctx.admission().register(_ -> new Admission.Reject("synaxis:match-unavailable")));
        final PluginHost host = host(env, backend);
        host.enable(List.of(probe), Set.of());

        final Player player = env.createPlayer(env.createFlatInstance(), new Pos(0, 41, 0));

        assertFalse(player.getPlayerConnection().isOnline());
        assertEquals(1, backend.loads.get());
        assertEquals(1, backend.releases.get(), "the lease taken for the load goes back");
        host.disableAll();
    }

    @Test
    void aPluginThatFailsToEnableLeavesNoSectionAndNoShutdownHookBehind(Env env) {
        final AtomicInteger defaults = new AtomicInteger();
        final AtomicInteger hooks = new AtomicInteger();
        final DataSection<AtomicInteger> bag = new DataSection<>() {
            @Override
            public String id() {
                return "broken:bag";
            }

            @Override
            public int schemaVersion() {
                return 1;
            }

            @Override
            public AtomicInteger createDefault() {
                defaults.incrementAndGet();
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
        final Instance match = env.createFlatInstance();
        final Probe broken = new Probe("broken", ctx -> {
            ctx.playerData().register(bag);
            ctx.lifecycle().onShutdownSave(_ -> hooks.incrementAndGet());
            throw new IllegalStateException("the test's plugin fails to enable");
        });
        final Probe admitting = new Probe("probe", ctx -> ctx.admission().register(_ -> new Admission.Accept(match, 1)));
        ledger = new EventLedger(workingDirectory.resolve("logs"), "test", EventLedger.DEFAULT_MAX_BYTES_PER_DAY);
        ledger.start();
        final PlayerStore store = new PlayerStore(env.process(), new MemoryBackend(), RedisLink.disabled("test"), "test",
                false, PlayerStore.Options.DEFAULT, ledger::record);
        final PluginHost host = new PluginHost(env.process(), workingDirectory.resolve("plugins"), ledger, store);
        host.enable(List.of(broken, admitting), Set.of());

        final Player player = env.createPlayer(match, new Pos(0, 41, 0));
        assertTrue(player.getPlayerConnection().isOnline());
        final var saved = store.saveAll(Duration.ofSeconds(5), true);
        env.tick();
        saved.join();

        assertEquals(0, defaults.get(), "no login loads a section of a plugin that is not there");
        assertEquals(0, hooks.get(), "no shutdown calls the hook of a plugin that is not there");
        host.disableAll();
    }

    private List<String> readLedger() throws IOException {
        Objects.requireNonNull(ledger).close();
        final List<String> lines = new ArrayList<>();
        try (var files = Files.list(workingDirectory.resolve("logs"))) {
            for (Path file : files.toList()) lines.addAll(Files.readAllLines(file));
        }
        return lines;
    }

    private static final class FailingBackend implements StoreBackend {
        @Override
        public LoadOutcome load(UUID account, String name, long sessionId) throws SQLException {
            throw new SQLException("the test database is down");
        }

        @Override
        public void write(long sessionId, List<Write> writes) throws SQLException {
            throw new SQLException("the test database is down");
        }

        @Override
        public void release(long characterId, long sessionId) throws SQLException {
            throw new SQLException("the test database is down");
        }

        @Override
        public void releaseAccount(UUID account, long sessionId) throws SQLException {
            throw new SQLException("the test database is down");
        }
    }

    private static final class CountingBackend implements StoreBackend {
        final MemoryBackend memory = new MemoryBackend();
        final AtomicInteger loads = new AtomicInteger();
        final AtomicInteger releases = new AtomicInteger();

        @Override
        public LoadOutcome load(UUID account, String name, long sessionId) {
            loads.incrementAndGet();
            return assertInstanceOf(LoadOutcome.Acquired.class, memory.load(account, name, sessionId));
        }

        @Override
        public void write(long sessionId, List<Write> writes) throws FencedException {
            memory.write(sessionId, writes);
        }

        @Override
        public void release(long characterId, long sessionId) {
            releases.incrementAndGet();
        }

        @Override
        public void releaseAccount(UUID account, long sessionId) {
            memory.releaseAccount(account, sessionId);
        }
    }
}
