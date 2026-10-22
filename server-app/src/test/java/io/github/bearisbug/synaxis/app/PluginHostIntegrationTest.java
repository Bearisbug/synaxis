package io.github.bearisbug.synaxis.app;

import ch.qos.logback.classic.Level;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.entity.TrackingRange;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.player.PlayerPluginMessageEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.plugin.Admission;
import io.github.bearisbug.synaxis.plugin.AdmissionProvider;
import io.github.bearisbug.synaxis.plugin.AdmissionRequest;
import io.github.bearisbug.synaxis.plugin.DisconnectKeys;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.PluginContext;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.timer.Task;
import io.github.bearisbug.synaxis.timer.TaskSchedule;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** The ledger of {@link #host}, started so that {@link #readLedger} can drain it to disk. */
    private @Nullable EventLedger ledger;

    /**
     * Stops the writer thread of the ledger before JUnit deletes {@link #workingDirectory}. Left
     * running, it creates {@code logs/} and the day's file whenever it gets round to the first line,
     * which on a loaded run can be after the test has returned: into the middle of the deletion
     * ({@code DirectoryNotEmptyException}) or after it (the temp directory comes back).
     */
    @AfterEach
    void closeLedger() {
        if (ledger != null) ledger.close();
    }

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
        private AdmissionProvider admission =
                request -> new Admission.Reject("synaxis:match-unavailable-" + request.username());
        private boolean throwFromDisable;
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
            if (registerAdmission) ctx.admission().register(admission);
        }

        @Override
        public void onDisable() {
            calls.add("disable:" + descriptor.id());
            if (throwFromDisable) throw new IllegalStateException("probe disable failure");
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
        ledger = new EventLedger(workingDirectory.resolve("logs"), "test", EventLedger.DEFAULT_MAX_BYTES_PER_DAY);
        ledger.start();
        return new PluginHost(env.process(), workingDirectory.resolve("plugins"), ledger);
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
    void aSelectedIdNoPluginAnswersToIsNamedInAWarning(Env env) {
        final ProbePlugin discovered = new ProbePlugin("rts");
        final PluginHost host = host(env);

        try (LogCapture logs = LogCapture.of(PluginHost.class)) {
            // A typo in synaxis.app.plugins otherwise comes out as the core mode and nothing else.
            host.enable(List.of(discovered), Set.of("rst"));

            final List<String> warnings = logs.messagesAt(Level.WARN);
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.getFirst().contains("rst"), warnings.getFirst());
            assertTrue(warnings.getFirst().contains("rts"), warnings.getFirst());
        }

        assertEquals(List.of(), host.enabledPluginIds());
        assertEquals(0, host.attemptedPluginCount());
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

    /**
     * The channels, the admission slot, the actions and the managed instances are kept by plugin id: of two
     * plugins with one id, the teardown of one would take the other's back too.
     */
    @Test
    void twoPluginsWithTheSameIdRefuseTheStartupBeforeEitherIsEnabled(Env env) {
        final ProbePlugin probe = new ProbePlugin("dup");
        final List<String> calls = new ArrayList<>();
        final SynaxisPlugin other = new SynaxisPlugin() {
            @Override
            public PluginDescriptor descriptor() {
                return new PluginDescriptor("dup", "2.0.0", PluginApi.API_VERSION);
            }

            @Override
            public void onEnable(PluginContext ctx) {
                calls.add("enable:other");
            }
        };
        final PluginHost host = host(env);

        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> host.enable(List.of(probe, other), Set.of()));

        assertTrue(refused.getMessage().contains("\"dup\"") && refused.getMessage().contains(ProbePlugin.class.getName())
                && refused.getMessage().contains(other.getClass().getName()), refused.getMessage());
        assertEquals(List.of(), probe.calls);
        assertEquals(List.of(), calls);
        assertEquals(List.of(), host.enabledPluginIds());
        assertFalse(host.hasAdmissionProvider());
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
        // Both took part, which is what keeps a plugin that came up wrong out of the core mode
        assertEquals(2, host.attemptedPluginCount());
        host.disableAll();
        assertEquals(List.of("enable:failing"), failing.calls, "a plugin that failed to enable is not disabled");
    }

    @Test
    void aPluginThrowingFromOnDisableIsTornDownLikeTheOthers(Env env) {
        final ProbePlugin probe = new ProbePlugin("probe");
        probe.throwFromDisable = true;
        final PluginHost host = host(env);
        host.enable(List.of(probe), Set.of());

        try (LogCapture logs = LogCapture.of(PluginHost.class)) {
            host.disableAll();

            final List<String> errors = logs.messagesAt(Level.ERROR);
            assertEquals(1, errors.size(), errors.toString());
            assertTrue(errors.getFirst().contains("plugin_id=probe"), errors.getFirst());
        }

        assertEquals(List.of(), host.enabledPluginIds());
        assertFalse(host.hasAdmissionProvider());
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

    /**
     * The 5 s budget is shared: a plugin disabled after one that spent it still gets its onDisable called, but the
     * line about it says the budget was gone before its turn rather than that it ran over.
     */
    @Test
    void aPluginWhoseTurnComesAfterTheBudgetIsSpentIsNotBlamedForIt(Env env) {
        final CountDownLatch release = new CountDownLatch(1);
        final List<String> calls = new CopyOnWriteArrayList<>();
        final ProbePlugin first = new ProbePlugin("first", calls);
        final ProbePlugin hanging = new ProbePlugin("hanging", calls);
        hanging.registerAdmission = false;
        hanging.disableRelease = release;
        final PluginHost host = host(env);
        host.enable(List.of(first, hanging), Set.of());

        try (LogCapture logs = LogCapture.of(PluginHost.class)) {
            host.disableAll();

            final List<String> errors = logs.messagesAt(Level.ERROR);
            assertEquals(2, errors.size(), errors.toString());
            assertTrue(errors.get(0).contains("still in onDisable") && errors.get(0).contains("plugin_id=hanging"),
                    errors.get(0));
            assertTrue(errors.get(1).contains("already spent") && errors.get(1).contains("plugin_id=first"),
                    errors.get(1));
            assertFalse(errors.get(1).contains("still in onDisable"), errors.get(1));
        } finally {
            release.countDown();
        }
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!calls.contains("disable:first") && System.nanoTime() < deadline) Thread.onSpinWait();
        assertTrue(calls.contains("disable:first"), "its onDisable is still called: " + calls);
        assertEquals(List.of(), host.enabledPluginIds());
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
                provider.admit(new AdmissionRequest(UUID.randomUUID(), "tester", 1, 1, UUID.randomUUID(),
                        InetAddress.getLoopbackAddress())));
        host.disableAll();
    }

    @Test
    void aConnectionIsTurnedAwayWhileNoPluginHoldsTheAdmissionSlot(Env env) {
        final ProbePlugin probe = new ProbePlugin("probe");
        probe.registerAdmission = false;
        final PluginHost host = host(env);
        host.enable(List.of(probe), Set.of());

        try (LogCapture logs = LogCapture.of(AdmissionGate.class)) {
            final Player player = env.createPlayer(env.createFlatInstance(), new Pos(0, 41, 0));

            final List<String> lines = logs.messagesAt(Level.INFO);
            assertEquals(1, lines.size(), lines.toString());
            assertTrue(lines.getFirst().contains(DisconnectKeys.MATCH_UNAVAILABLE), lines.getFirst());
            assertFalse(player.getPlayerConnection().isOnline());
        }
        host.disableAll();
    }

    @Test
    void anAdmissionProviderThatThrowsTurnsTheConnectionAwayAgainstItsPlugin(Env env) {
        final ProbePlugin probe = new ProbePlugin("probe");
        probe.admission = _ -> {
            throw new IllegalStateException("probe admission failure");
        };
        final PluginHost host = host(env);
        host.enable(List.of(probe), Set.of());

        try (LogCapture logs = LogCapture.of(AdmissionGate.class)) {
            final Player player = env.createPlayer(env.createFlatInstance(), new Pos(0, 41, 0));

            final List<String> errors = logs.messagesAt(Level.ERROR);
            assertEquals(1, errors.size(), errors.toString());
            assertTrue(errors.getFirst().contains("plugin_id=probe"), errors.getFirst());
            assertFalse(player.getPlayerConnection().isOnline());
        }
        host.disableAll();
    }

    /**
     * {@code API-M2-011}: an answer of null, and an answer whose constructor refuses what the provider gave it,
     * are failures to decide like an exception: the connection is turned away with match-unavailable,
     * {@code client_rejected} is written, and the ERROR line names the plugin.
     */
    @Test
    void anAdmissionProviderThatAnswersNothingOrABrokenAnswerTurnsTheConnectionAwayAgainstItsPlugin(Env env) {
        final ProbePlugin probe = new ProbePlugin("probe");
        final Queue<Supplier<Admission>> answers = new ConcurrentLinkedQueue<>(List.of(
                PluginHostIntegrationTest::nothing,
                () -> new Admission.Accept(noInstance()),
                () -> new Admission.Reject(noKey())));
        probe.admission = _ -> answers.remove().get();
        final PluginHost host = host(env);
        host.enable(List.of(probe), Set.of());
        final Instance match = env.createFlatInstance();

        try (LogCapture logs = LogCapture.of(AdmissionGate.class)) {
            for (int i = 0; i < 3; i++) {
                final Player player = env.createPlayer(match, new Pos(0, 41, 0));
                assertFalse(player.getPlayerConnection().isOnline(), "answer " + i + " let the connection in");
            }
            final List<String> errors = logs.messagesAt(Level.ERROR);
            assertEquals(3, errors.size(), errors.toString());
            for (String error : errors) assertTrue(error.contains("plugin_id=probe"), error);
            final List<String> refusals = logs.messagesAt(Level.INFO);
            assertEquals(3, refusals.size(), refusals.toString());
            for (String refusal : refusals)
                assertTrue(refusal.contains("reason=" + DisconnectKeys.MATCH_UNAVAILABLE + " cause=the admission provider threw"),
                        refusal);
        }
        host.disableAll();
        final List<String> rejected = readLedger().stream().filter(line -> line.contains("\"client_rejected\"")).toList();
        assertEquals(3, rejected.size(), rejected.toString());
        for (String line : rejected) assertTrue(line.contains("\"reason\":\"match-unavailable\""), line);
    }

    /** What a provider returns when it breaks the not-null contract of {@code admit}. */
    private static @Nullable Admission nothing() {
        return null;
    }

    private static @Nullable Instance noInstance() {
        return null;
    }

    private static @Nullable String noKey() {
        return null;
    }

    /** A provider of null would hold the slot and turn every connection away: the plugin fails to enable instead. */
    @Test
    void registeringNoAdmissionProviderFailsTheEnableOfThePlugin(Env env) {
        final ProbePlugin probe = new ProbePlugin("probe");
        probe.admission = noProvider();
        final PluginHost host = host(env);

        try (LogCapture logs = LogCapture.of(PluginHost.class)) {
            host.enable(List.of(probe), Set.of());

            final List<String> errors = logs.messagesAt(Level.ERROR);
            assertEquals(1, errors.size(), errors.toString());
            assertTrue(errors.getFirst().contains("Plugin failed to enable: plugin_id=probe"), errors.getFirst());
        }
        assertFalse(host.hasAdmissionProvider(), "the slot was taken by nothing");
        assertEquals(List.of(), host.enabledPluginIds());
    }

    private static @Nullable AdmissionProvider noProvider() {
        return null;
    }

    @Test
    void anAcceptedConnectionIsLetThrough(Env env) {
        final ProbePlugin probe = new ProbePlugin("probe");
        final PluginHost host = host(env);
        final Instance match = env.createFlatInstance();
        probe.admission = _ -> new Admission.Accept(match);
        host.enable(List.of(probe), Set.of());

        try (LogCapture logs = LogCapture.of(AdmissionGate.class)) {
            final Player player = env.createPlayer(match, new Pos(0, 41, 0));

            assertEquals(List.of(), logs.messagesAt(Level.INFO));
            assertTrue(player.getPlayerConnection().isOnline());
            assertEquals(match, player.getInstance());
        }
        host.disableAll();
    }

    @Test
    void anAdmissionProviderSlowerThanItsBudgetStillDecidesAndIsWarnedAbout(Env env) {
        final ProbePlugin probe = new ProbePlugin("probe");
        final PluginHost host = host(env);
        final Instance match = env.createFlatInstance();
        probe.admission = _ -> {
            sleepQuietly(AdmissionGate.BUDGET_MILLIS + 50);
            return new Admission.Accept(match);
        };
        host.enable(List.of(probe), Set.of());

        try (LogCapture logs = LogCapture.of(AdmissionGate.class)) {
            final Player player = env.createPlayer(match, new Pos(0, 41, 0));

            final List<String> warnings = logs.messagesAt(Level.WARN);
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.getFirst().contains("plugin_id=probe"), warnings.getFirst());
            assertTrue(admitMillis(warnings.getFirst()) >= AdmissionGate.BUDGET_MILLIS, warnings.getFirst());
            // The budget is measured after the fact, so the answer stands: a slow provider does not
            // turn into a rejecting one.
            assertTrue(player.getPlayerConnection().isOnline());
            assertEquals(match, player.getInstance());
        }
        host.disableAll();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static long admitMillis(String line) {
        final Matcher matcher = Pattern.compile("admit_ms=(\\d+)").matcher(line);
        assertTrue(matcher.find(), line);
        return Long.parseLong(matcher.group(1));
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

    /** {@code docs/DESIGN-PROD.md} API-PROD-019: a plugin's category range lands in the process table. */
    @Test
    void aPluginSetsTheTrackingRangeOfAnEntityType(Env env) {
        final PluginHost host = host(env);
        host.enable(List.of(new SynaxisPlugin() {
            @Override
            public PluginDescriptor descriptor() {
                return new PluginDescriptor("ranges", "1.0.0", PluginApi.API_VERSION);
            }

            @Override
            public void onEnable(PluginContext ctx) {
                ctx.entities().setDefaultTrackingRange(EntityType.ITEM, 24, 8);
            }
        }), Set.of());
        try {
            assertEquals(new TrackingRange(24, 8), env.process().trackingRanges().getDefault(EntityType.ITEM));
        } finally {
            host.disableAll();
        }
    }

    /**
     * A plugin the selection cannot read an id from refuses the start whether it is selected or not, and a refused
     * descriptor names the plugin class: the id alone does not say which jar to look in.
     */
    @Test
    void aDescriptorWithoutAnIdOrVersionRefusesTheStartNamingThePluginClass(Env env) {
        final ProbePlugin legal = new ProbePlugin("legal");
        final PluginHost host = host(env);

        assertRefusedNaming(DescribedPlugin.class,
                () -> host.enable(List.of(legal, new DescribedPlugin(null)), Set.of("legal")));
        assertRefusedNaming(DescribedPlugin.class, () -> host.enable(List.of(legal,
                new DescribedPlugin(new PluginDescriptor(noText(), "1.0.0", PluginApi.API_VERSION))), Set.of("legal")));
        assertRefusedNaming(DescribedPlugin.class, () -> host.enable(List.of(legal,
                new DescribedPlugin(new PluginDescriptor("versionless", noText(), PluginApi.API_VERSION))), Set.of()));
        assertRefusedNaming(ProbePlugin.class, () -> host.enable(List.of(new ProbePlugin("Illegal Id")), Set.of()));

        assertEquals(List.of(), legal.calls);
        assertEquals(List.of(), host.enabledPluginIds());
    }

    private static void assertRefusedNaming(Class<?> pluginClass, Runnable enable) {
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, enable::run);
        assertTrue(refused.getMessage().contains(pluginClass.getName()), refused.getMessage());
    }

    /** What a broken plugin reports where its descriptor should hold text. */
    private static @Nullable String noText() {
        return null;
    }

    /** A plugin reporting the descriptor the test gives it, a broken one included. */
    private static final class DescribedPlugin implements SynaxisPlugin {

        private final @Nullable PluginDescriptor descriptor;

        DescribedPlugin(@Nullable PluginDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public @Nullable PluginDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public void onEnable(PluginContext ctx) {
            // Nothing to register
        }
    }

    /**
     * What a plugin registers once it is taken back, from a thread it left running, is dropped: the one shot
     * teardown that would take it back has already run (REQ-M2-004).
     */
    @Test
    void aChannelOrTabListVisibilityRegisteredAfterTheTeardownIsDropped(Env env) {
        final List<PluginContext> kept = new ArrayList<>();
        final PluginHost host = host(env);
        host.enable(List.of(new SynaxisPlugin() {
            @Override
            public PluginDescriptor descriptor() {
                return new PluginDescriptor("keeper", "1.0.0", PluginApi.API_VERSION);
            }

            @Override
            public void onEnable(PluginContext ctx) {
                kept.add(ctx);
            }
        }), Set.of());
        host.disableAll();

        final PluginContext ctx = kept.getFirst();
        final AtomicInteger payloads = new AtomicInteger();
        ctx.channels().register("late", (_, _) -> payloads.incrementAndGet());
        ctx.tabList().setVisibility((_, _) -> false);

        final Instance instance = env.createFlatInstance();
        final Player viewer = env.createPlayer(instance, new Pos(0, 41, 0));
        final Player subject = env.createPlayer(instance, new Pos(1, 41, 0));
        env.process().eventHandler().call(new PlayerPluginMessageEvent(viewer, "keeper:late", PAYLOAD));
        assertEquals(0, payloads.get(), "a channel registered after the teardown got a payload");
        assertTrue(env.process().connection().isListedFor(viewer, subject),
                "a tab list visibility set after the teardown applies");
    }

    private List<String> readLedger() {
        // The writer thread owns the file; closing drains what is queued and forces it
        Objects.requireNonNull(ledger, "only a test that built a host has a ledger").close();
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
