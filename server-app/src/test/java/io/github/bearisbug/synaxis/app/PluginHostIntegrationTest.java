package io.github.bearisbug.synaxis.app;

import ch.qos.logback.classic.Level;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.player.PlayerPluginMessageEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.plugin.Admission;
import io.github.bearisbug.synaxis.plugin.AdmissionRequest;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.PluginContext;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.timer.Task;
import io.github.bearisbug.synaxis.timer.TaskSchedule;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plugin host of REQ-M2-003 to REQ-M2-006 and the admission slot of REQ-M2-024: discovery,
 * three step lifecycle, the one shot teardown and the exception attribution of the three wrappers.
 */
@EnvTest
class PluginHostIntegrationTest {

    private static final byte[] PAYLOAD = "ping".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path workingDirectory;

    private record ProbeEvent() implements Event {
    }

    /**
     * A plugin whose registrations and failure modes are chosen by the test.
     */
    private static final class ProbePlugin implements SynaxisPlugin {

        private final PluginDescriptor descriptor;
        private final List<String> calls;
        private final AtomicInteger events = new AtomicInteger();
        private final AtomicInteger payloads = new AtomicInteger();
        private final AtomicInteger ticks = new AtomicInteger();

        private boolean failOnEnable;
        private boolean throwFromCallbacks;
        private boolean registerAdmission = true;
        private @Nullable CountDownLatch disableRelease;
        private volatile boolean disableReturned;
        private @Nullable Task task;

        ProbePlugin(String id, List<String> calls) {
            this.descriptor = new PluginDescriptor(id, "1.0.0", PluginApi.API_VERSION);
            this.calls = calls;
        }

        ProbePlugin(String id) {
            this(id, new ArrayList<>());
        }

        @Override
        public PluginDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public void onEnable(PluginContext ctx) {
            calls.add("enable:" + descriptor.id());
            if (failOnEnable) throw new IllegalStateException("probe failure");
            ctx.events().addListener(ProbeEvent.class, _ -> {
                events.incrementAndGet();
                if (throwFromCallbacks) throw new IllegalStateException("probe event failure");
            });
            ctx.channels().register("probe", (_, _) -> {
                payloads.incrementAndGet();
                if (throwFromCallbacks) throw new IllegalStateException("probe channel failure");
            });
            this.task = ctx.scheduler().scheduleTask(() -> {
                ticks.incrementAndGet();
                if (throwFromCallbacks) throw new IllegalStateException("probe task failure");
            }, TaskSchedule.immediate(), TaskSchedule.nextTick());
            if (registerAdmission) {
                ctx.admission().register(request -> new Admission.Reject("synaxis:match-unavailable-" + request.username()));
            }
        }

        @Override
        public void onDisable() {
            calls.add("disable:" + descriptor.id());
            final CountDownLatch release = disableRelease;
            // Bounded, so that a host without a disable budget fails this test instead of hanging
            // the whole regression run.
            if (release != null) awaitQuietly(release);
            disableReturned = true;
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private PluginHost host(Env env) {
        return new PluginHost(env.process(), workingDirectory.resolve("plugins"),
                new EventLedger(workingDirectory.resolve("logs")));
    }

    @Test
    void discoveryFindsTheCompileTimePlugin() {
        final List<String> ids = PluginHost.discover().stream()
                .map(plugin -> plugin.descriptor().id())
                .toList();
        assertTrue(ids.contains("rts"), "ServiceLoader did not find the rts plugin: " + ids);
    }

    @Test
    void enablesInOrderAndDisablesInReverse(Env env) {
        final List<String> calls = new ArrayList<>();
        final ProbePlugin first = new ProbePlugin("first", calls);
        final ProbePlugin second = new ProbePlugin("second", calls);
        second.registerAdmission = false;
        final PluginHost host = host(env);

        host.enable(List.of(first, second), Set.of());
        assertEquals(List.of("first", "second"), host.enabledPluginIds());
        assertTrue(Files.isDirectory(workingDirectory.resolve("plugins").resolve("first")));
        assertTrue(Files.isDirectory(workingDirectory.resolve("plugins").resolve("second")));

        host.disableAll();
        assertEquals(List.of("enable:first", "enable:second", "disable:second", "disable:first"), calls);
        assertEquals(List.of(), host.enabledPluginIds());
    }

    @Test
    void theSelectionPropertyPicksWhichPluginsAreEnabled(Env env) {
        final ProbePlugin wanted = new ProbePlugin("wanted");
        final ProbePlugin skipped = new ProbePlugin("skipped");
        final PluginHost host = host(env);

        host.enable(List.of(wanted, skipped), Set.of("wanted"));

        assertEquals(List.of("wanted"), host.enabledPluginIds());
        assertEquals(List.of("enable:wanted"), wanted.calls);
        assertEquals(List.of(), skipped.calls);
        host.disableAll();
    }

    @Test
    void anIllegalDescriptorStopsTheStartupBeforeAnyPluginIsEnabled(Env env) {
        final ProbePlugin legal = new ProbePlugin("legal");
        final PluginHost host = host(env);

        assertThrows(IllegalArgumentException.class,
                () -> host.enable(List.of(legal, new ProbePlugin("Illegal Id")), Set.of()));

        assertEquals(List.of(), legal.calls);
        assertEquals(List.of(), host.enabledPluginIds());
    }

    @Test
    void aPluginFailingToEnableIsTornDownWhileTheProcessGoesOn(Env env) {
        final ProbePlugin failing = new ProbePlugin("failing");
        failing.failOnEnable = true;
        final ProbePlugin healthy = new ProbePlugin("healthy");
        final PluginHost host = host(env);

        try (LogCapture logs = LogCapture.of(PluginHost.class)) {
            host.enable(List.of(failing, healthy), Set.of());
            final List<String> errors = logs.messagesAt(Level.ERROR);
            assertEquals(1, errors.size(), errors.toString());
            assertTrue(errors.getFirst().contains("plugin_id=failing"), errors.getFirst());
        }

        assertEquals(List.of("healthy"), host.enabledPluginIds());
        host.disableAll();
    }

    @Test
    void aPluginHangingInOnDisableDoesNotHoldTheShutdown(Env env) {
        final CountDownLatch release = new CountDownLatch(1);
        final ProbePlugin probe = new ProbePlugin("probe");
        probe.disableRelease = release;
        final PluginHost host = host(env);
        host.enable(List.of(probe), Set.of());

        try (LogCapture logs = LogCapture.of(PluginHost.class)) {
            host.disableAll();

            // Back before the plugin is done, which is the whole point of the budget.
            assertFalse(probe.disableReturned);
            final List<String> errors = logs.messagesAt(Level.ERROR);
            assertEquals(1, errors.size(), errors.toString());
            assertTrue(errors.getFirst().contains("plugin_id=probe"), errors.getFirst());
        } finally {
            release.countDown();
        }

        assertEquals(List.of(), host.enabledPluginIds());
        assertFalse(host.hasAdmissionProvider());
    }

    @Test
    void disablingTakesBackEventsTasksChannelsAndAdmission(Env env) {
        final ProbePlugin probe = new ProbePlugin("probe");
        final PluginHost host = host(env);
        host.enable(List.of(probe), Set.of());

        final Instance instance = env.createFlatInstance();
        final Player player = env.createPlayer(instance, new Pos(0, 41, 0));

        env.process().eventHandler().call(new ProbeEvent());
        assertEquals(1, probe.events.get());
        env.process().eventHandler().call(new PlayerPluginMessageEvent(player, "probe:probe", PAYLOAD));
        assertEquals(1, probe.payloads.get());
        env.tick();
        assertTrue(probe.ticks.get() >= 1);
        assertNotNull(host.admissionProvider());

        final Task task = probe.task;
        assertNotNull(task);
        host.disableAll();

        assertFalse(task.isAlive());
        assertFalse(host.hasAdmissionProvider());

        final int eventsBefore = probe.events.get();
        final int payloadsBefore = probe.payloads.get();
        final int ticksBefore = probe.ticks.get();
        try (LogCapture logs = LogCapture.of(PluginChannelRouter.class)) {
            env.process().eventHandler().call(new ProbeEvent());
            env.process().eventHandler().call(new PlayerPluginMessageEvent(player, "probe:probe", PAYLOAD));
            env.tick();
            env.tick();

            final List<String> warnings = logs.messagesAt(Level.WARN);
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.getFirst().contains("probe:probe"), warnings.getFirst());
        }
        assertEquals(eventsBefore, probe.events.get());
        assertEquals(payloadsBefore, probe.payloads.get());
        assertEquals(ticksBefore, probe.ticks.get());
    }

    @Test
    void aPayloadOnAnUnregisteredChannelIsDroppedWithOneWarning(Env env) {
        final ProbePlugin probe = new ProbePlugin("probe");
        final PluginHost host = host(env);
        host.enable(List.of(probe), Set.of());

        final Instance instance = env.createFlatInstance();
        final Player player = env.createPlayer(instance, new Pos(0, 41, 0));

        try (LogCapture logs = LogCapture.of(PluginChannelRouter.class)) {
            env.process().eventHandler().call(new PlayerPluginMessageEvent(player, "zz:game", PAYLOAD));
            final List<String> warnings = logs.messagesAt(Level.WARN);
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.getFirst().contains("zz:game"), warnings.getFirst());
            assertTrue(warnings.getFirst().contains(player.getPlayerConnection().connectionId().toString()),
                    warnings.getFirst());
        }
        assertEquals(0, probe.payloads.get());
        host.disableAll();
    }

    @Test
    void eachWrapperLogsTheExceptionOnceAgainstThePlugin(Env env) {
        final ProbePlugin probe = new ProbePlugin("probe");
        probe.throwFromCallbacks = true;
        final PluginHost host = host(env);
        host.enable(List.of(probe), Set.of());

        final Instance instance = env.createFlatInstance();
        final Player player = env.createPlayer(instance, new Pos(0, 41, 0));

        try (LogCapture events = LogCapture.of(PluginEventsImpl.class)) {
            env.process().eventHandler().call(new ProbeEvent());
            assertAttributed(events.messagesAt(Level.ERROR));
        }
        try (LogCapture channels = LogCapture.of(PluginChannelRouter.class)) {
            env.process().eventHandler().call(new PlayerPluginMessageEvent(player, "probe:probe", PAYLOAD));
            assertAttributed(channels.messagesAt(Level.ERROR));
        }
        try (LogCapture tasks = LogCapture.of(PluginSchedulerImpl.class)) {
            env.tick();
            assertAttributed(tasks.messagesAt(Level.ERROR));
        }

        assertEquals(List.of("probe"), host.enabledPluginIds());
        host.disableAll();
    }

    private static void assertAttributed(List<String> errors) {
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.getFirst().contains("plugin_id=probe"), errors.getFirst());
    }

    @Test
    void theProcessAcceptsOnlyOneAdmissionProvider(Env env) {
        final ProbePlugin first = new ProbePlugin("first");
        final ProbePlugin second = new ProbePlugin("second");
        final PluginHost host = host(env);

        try (LogCapture logs = LogCapture.of(PluginHost.class)) {
            host.enable(List.of(first, second), Set.of());
            final List<String> errors = logs.messagesAt(Level.ERROR);
            assertEquals(1, errors.size(), errors.toString());
            assertTrue(errors.getFirst().contains("plugin_id=second"), errors.getFirst());
        }

        assertEquals(List.of("first"), host.enabledPluginIds());
        final var provider = host.admissionProvider();
        assertNotNull(provider);
        assertEquals(new Admission.Reject("synaxis:match-unavailable-tester"),
                provider.admit(new AdmissionRequest(UUID.randomUUID(), "tester", 1, 1)));
        host.disableAll();
    }

    @Test
    void enablingRecordsOneLedgerLine(Env env) {
        final ProbePlugin probe = new ProbePlugin("probe");
        final PluginHost host = host(env);
        host.enable(List.of(probe), Set.of());
        host.disableAll();

        final List<String> lines = readLedger();
        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.getFirst().contains("\"plugin_enabled\""), lines.getFirst());
        assertTrue(lines.getFirst().contains("\"plugin_id\":\"probe\""), lines.getFirst());
        assertTrue(lines.getFirst().contains("\"enable_ms\""), lines.getFirst());
    }

    private List<String> readLedger() {
        final Path directory = workingDirectory.resolve("logs");
        try (var files = Files.list(directory)) {
            final List<String> lines = new ArrayList<>();
            for (Path file : files.toList()) lines.addAll(Files.readAllLines(file));
            return lines;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
