package io.github.bearisbug.synaxis.app;

import ch.qos.logback.classic.Level;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.ChunkLoader;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.generator.Generator;
import io.github.bearisbug.synaxis.plugin.Admission;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.PluginContext;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;
import io.github.bearisbug.synaxis.plugin.instance.EndCause;
import io.github.bearisbug.synaxis.plugin.instance.InstanceSpec;
import io.github.bearisbug.synaxis.plugin.instance.ManagedInstance;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plugin host takes the managed instances of a plugin back ({@code docs/DESIGN-PLAY.md}
 * {@code API-PLAY-006} 收回, §11.1 forced close): when its {@code onEnable} throws, and when the process
 * stops. The startup decision of {@code API-M2-010} step 5 counts the registered instances only
 * ({@code Main.java:312}, {@code :930}), which an instance taken back or never prepared is not.
 */
@EnvTest
class PluginHostInstancesIntegrationTest {

    static final Generator FLAT = unit -> unit.modifier().fillHeight(0, 10, Block.STONE);

    @TempDir
    Path workingDirectory;

    private @Nullable EventLedger ledger;

    /** Stops the ledger's writer before JUnit deletes the working directory, as in PluginHostIntegrationTest. */
    @AfterEach
    void closeLedger() {
        if (ledger != null) ledger.close();
    }

    /** A plugin whose {@code onEnable} is the test's. */
    private record ProbePlugin(PluginDescriptor descriptor, Consumer<PluginContext> enable) implements SynaxisPlugin {

        ProbePlugin(Consumer<PluginContext> enable) {
            this(new PluginDescriptor("probe", "1.0.0", PluginApi.API_VERSION), enable);
        }

        @Override
        public void onEnable(PluginContext context) {
            enable.accept(context);
        }
    }

    private PluginHost host(Env env) {
        Main.registerMatchDimensionType(env.process());
        final EventLedger opened = new EventLedger(workingDirectory.resolve("logs"), "test",
                EventLedger.DEFAULT_MAX_BYTES_PER_DAY);
        opened.start();
        this.ledger = opened;
        return new PluginHost(env.process(), workingDirectory.resolve("plugins"), opened);
    }

    private List<String> readLedger() {
        // The writer thread owns the file; closing drains what is queued and forces it
        Objects.requireNonNull(ledger, "only a test that built a host has a ledger").close();
        try (var files = Files.list(workingDirectory.resolve("logs"))) {
            final List<String> lines = new ArrayList<>();
            for (Path file : files.toList()) lines.addAll(Files.readAllLines(file));
            return lines;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> closedLines(List<String> lines) {
        return lines.stream().filter(line -> line.contains("\"event\":\"play_instance_closed\"")).toList();
    }

    /** §19 「onEnable 失败时收回已建的实例」. */
    @Test
    void theInstancesOfAPluginWhoseOnEnableThrewAreTakenBack(Env env) {
        final List<ManagedInstance> created = new ArrayList<>();
        final PluginHost host = host(env);

        host.enable(List.of(new ProbePlugin(context -> {
            final ManagedInstance arena = context.instances().create(new InstanceSpec("arena", 1, FLAT));
            created.add(arena);
            arena.start();
            throw new IllegalStateException("probe failure");
        })), Set.of());

        final ManagedInstance arena = created.getFirst();
        assertEquals(ManagedInstance.State.CLOSED, arena.state());
        assertEquals(EndCause.PLUGIN_DISABLED, arena.endCause());
        assertFalse(arena.instance().isRegistered());
        assertEquals(0, env.process().instance().getInstances().size());
        assertEquals(CoreMode.Mode.REFUSED, CoreMode.decide(CoreMode.Setting.AUTO, host.attemptedPluginCount(),
                host.hasAdmissionProvider(), env.process().instance().getInstances().size()),
                "the instance taken back counts as no running match");
        final List<String> closed = closedLines(readLedger());
        assertEquals(1, closed.size(), closed.toString());
        assertTrue(closed.getFirst().contains("\"cause\":\"plugin_disabled\""), closed.getFirst());
        assertTrue(closed.getFirst().contains("\"kind_id\":\"probe:arena\""), closed.getFirst());
    }

    /** {@code API-PLAY-006} 收回: the shutdown's {@code disableAll} closes what is still open. */
    @Test
    void theShutdownTakesBackAnOpenInstance(Env env) {
        final List<ManagedInstance> created = new ArrayList<>();
        final PluginHost host = host(env);
        host.enable(List.of(new ProbePlugin(context ->
                created.add(context.instances().create(new InstanceSpec("lobby", 1, FLAT)))
        )), Set.of());
        final ManagedInstance lobby = created.getFirst();
        assertTrue(lobby.instance().isRegistered());

        host.disableAll();

        assertEquals(ManagedInstance.State.CLOSED, lobby.state());
        assertEquals(EndCause.SHUTDOWN, lobby.endCause());
        assertFalse(lobby.instance().isRegistered());
        final List<String> closed = closedLines(readLedger());
        assertEquals(1, closed.size(), closed.toString());
        assertTrue(closed.getFirst().contains("\"cause\":\"shutdown\""), closed.getFirst());
    }

    /**
     * The shutdown takes every plugin back even when one teardown throws, here the forced close of an instance
     * whose chunk loader fails to unload: one ERROR line names that plugin, and the plugin enabled before it,
     * disabled after it, is still taken back.
     */
    @Test
    void aTeardownThatThrowsLeavesTheOtherPluginsTakenBack(Env env) {
        final List<ManagedInstance> created = new ArrayList<>();
        final PluginHost host = host(env);
        host.enable(List.of(
                new ProbePlugin(new PluginDescriptor("first", "1.0.0", PluginApi.API_VERSION), context -> {
                    final ManagedInstance lobby = context.instances().create(new InstanceSpec("lobby", 1, FLAT));
                    created.add(lobby);
                    context.admission().register(_ -> new Admission.Accept(lobby.instance()));
                }),
                new ProbePlugin(new PluginDescriptor("second", "1.0.0", PluginApi.API_VERSION), context -> {
                    final ManagedInstance arena = context.instances().create(new InstanceSpec("arena", 1, FLAT));
                    arena.instance().setChunkLoader(new FailingToUnload());
                    created.add(arena);
                })), Set.of());

        try (LogCapture logs = LogCapture.of(PluginHost.class)) {
            host.disableAll();

            final List<String> errors = logs.messagesAt(Level.ERROR);
            assertEquals(1, errors.size(), errors.toString());
            assertTrue(errors.getFirst().startsWith("Plugin teardown failed: plugin_id=second"), errors.getFirst());
        }
        final ManagedInstance lobby = created.getFirst();
        assertEquals(EndCause.SHUTDOWN, lobby.endCause());
        assertFalse(lobby.instance().isRegistered(), "the plugin after the failing teardown was not taken back");
        assertFalse(host.hasAdmissionProvider());
        assertEquals(List.of(), host.enabledPluginIds());
    }

    /** A chunk loader that loads nothing, and throws when a chunk is unloaded. */
    private static final class FailingToUnload implements ChunkLoader {

        @Override
        public @Nullable Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
            return null;
        }

        @Override
        public void saveChunk(Chunk chunk) {
        }

        @Override
        public void unloadChunk(Chunk chunk) {
            throw new IllegalStateException("probe unload failure");
        }
    }

    /** {@code API-PLAY-006} 起服判定: an instance that failed to prepare was never registered, so it is not counted. */
    @Test
    void theStartupDecisionCountsTheRegisteredInstancesOnly(Env env) {
        final List<ManagedInstance> created = new ArrayList<>();
        final PluginHost host = host(env);

        host.enable(List.of(new ProbePlugin(context -> {
            final ManagedInstance lobby = context.instances().create(new InstanceSpec("lobby", 1, FLAT));
            created.add(lobby);
            created.add(context.instances().create(new InstanceSpec("broken", 1, _ -> {
                throw new IllegalStateException("probe generator failure");
            })));
            context.admission().register(_ -> new Admission.Accept(lobby.instance()));
        })), Set.of());

        assertEquals(ManagedInstance.State.OPEN, created.get(0).state());
        assertEquals(ManagedInstance.State.CLOSED, created.get(1).state());
        assertEquals(EndCause.PREPARE_FAILED, created.get(1).endCause());
        assertEquals(1, env.process().instance().getInstances().size());
        assertEquals(CoreMode.Mode.GAMEPLAY, CoreMode.decide(CoreMode.Setting.AUTO, host.attemptedPluginCount(),
                host.hasAdmissionProvider(), env.process().instance().getInstances().size()));
        host.disableAll();
    }
}
