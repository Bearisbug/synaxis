package io.github.bearisbug.synaxis;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.event.player.PlayerDisconnectEvent;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.PacketReading;
import io.github.bearisbug.synaxis.network.packet.PacketWriting;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginStartPacket;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginDisconnectPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.thread.TickThread;
import io.github.bearisbug.synaxis.timer.TaskSchedule;
import net.kyori.adventure.text.TranslatableComponent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shutdown sequence of {@code docs/DESIGN-PROD.md} §11.4 (REQ-PROD-008, API-PROD-007) on a
 * started process, whose tick scheduler thread keeps ticking through it: gate, countdown, save,
 * kick, plugins, stop, and the three transitions the tick watchdog drives.
 */
public class ShutdownSequenceTest {

    /** Records what the engine asks of the host, in order, with what the process looked like. */
    private static final class Recorder implements ShutdownParticipant {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final AtomicReference<CompletableFuture<?>> nextSave = new AtomicReference<>(CompletableFuture.completedFuture(null));
        volatile ServerProcess process;
        volatile boolean aliveWhileSaving;
        volatile Duration firstBudget;
        volatile long saveEndedAt;

        @Override
        public void onGateClosed(Duration countdown) {
            calls.add("gate_closed:" + countdown.toSeconds() + ":" + process.connection().gate().isClosed());
        }

        @Override
        public CompletableFuture<?> saveAll(Duration budget, boolean tickAlive) {
            if (firstBudget == null) firstBudget = budget;
            aliveWhileSaving = process.isAlive();
            calls.add("save:" + budget.toSeconds() + ":" + tickAlive + ":" + process.shutdownPhase());
            return nextSave.get();
        }

        @Override
        public void onSaveEnded(boolean timedOut) {
            saveEndedAt = System.nanoTime();
            calls.add("save_ended:" + timedOut);
        }
    }

    @Test
    @Timeout(60)
    public void theTickRunsThroughSavingAndKickingAndThePluginsGoLast() throws Exception {
        final Recorder recorder = new Recorder();
        final ServerProcess process = started(recorder);
        final AtomicInteger ticks = new AtomicInteger();
        process.scheduler().scheduleTask(() -> {
            ticks.incrementAndGet();
            return TaskSchedule.nextTick();
        }, TaskSchedule.nextTick());
        final Player player = spawn(process);
        final AtomicReference<Thread> disconnectedOn = new AtomicReference<>();
        process.eventHandler().addListener(PlayerDisconnectEvent.class, _ -> {
            recorder.calls.add("disconnect");
            disconnectedOn.set(Thread.currentThread());
        });
        final AtomicReference<Thread> shutdownTaskOn = new AtomicReference<>();
        process.scheduler().buildShutdownTask(() -> {
            recorder.calls.add("plugins_disabled:" + process.isAlive());
            shutdownTaskOn.set(Thread.currentThread());
        });
        // The save only completes once the tick has advanced while it waited
        final CompletableFuture<Void> save = new CompletableFuture<>();
        recorder.nextSave.set(save);
        final Thread watcher = Thread.ofVirtual().start(() -> {
            awaitQuietly(() -> process.shutdownPhase() == ServerProcess.ShutdownPhase.SAVING);
            final int before = ticks.get();
            awaitQuietly(() -> ticks.get() >= before + 3);
            recorder.calls.add("ticked_while_saving");
            save.complete(null);
        });

        process.stop();
        watcher.join();

        assertEquals(List.of("gate_closed:0:true", "save:30:true:SAVING", "ticked_while_saving", "save_ended:false",
                "disconnect", "plugins_disabled:true"), recorder.calls);
        assertTrue(recorder.aliveWhileSaving, "isAlive() stays true until the last step");
        assertInstanceOf(TickThread.class, disconnectedOn.get(), "the kicked player leaves on its tick thread");
        assertInstanceOf(TickSchedulerThread.class, shutdownTaskOn.get(), "onDisable runs between two ticks");
        assertTrue(player.isRemoved());
        assertEquals(ServerProcess.ShutdownPhase.STOPPED, process.shutdownPhase());
        assertFalse(process.isAlive());

        process.stop();
        assertEquals(6, recorder.calls.size(), "a second stop returns at once");
    }

    @Test
    @Timeout(60)
    public void aLoginDuringTheCountdownIsToldTheServerIsStopping() throws Exception {
        final Recorder recorder = new Recorder();
        final ServerProcess process = started(recorder);
        assertTrue(process.beginShutdown(Duration.ofSeconds(3)));
        awaitQuietly(() -> process.shutdownPhase() == ServerProcess.ShutdownPhase.GATE_CLOSED);

        try (SocketChannel channel = SocketChannel.open(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                process.server().getPort()))) {
            channel.socket().setSoTimeout(5_000);
            send(channel, ConnectionState.HANDSHAKE, new ClientHandshakePacket(MinecraftServer.PROTOCOL_VERSION,
                    "localhost", process.server().getPort(), ClientHandshakePacket.Intent.LOGIN));
            send(channel, ConnectionState.LOGIN, new ClientLoginStartPacket("Latecomer", UUID.randomUUID()));
            final ServerPacket answer = read(channel, process);
            final LoginDisconnectPacket disconnect = assertInstanceOf(LoginDisconnectPacket.class, answer);
            assertEquals("synaxis:server-stopping",
                    assertInstanceOf(TranslatableComponent.class, disconnect.kickMessage()).key());
        }
        assertTrue(process.isAlive(), "the countdown still runs");
        awaitQuietly(() -> process.shutdownPhase() == ServerProcess.ShutdownPhase.STOPPED);
        assertEquals(List.of("gate_closed:3:true", "save:30:true:SAVING", "save_ended:false"), recorder.calls);
    }

    @Test
    @Timeout(60)
    public void aDeathDuringTheCountdownSkipsToADeadSave() throws Exception {
        final Recorder recorder = new Recorder();
        final ServerProcess process = started(recorder);
        assertTrue(process.beginShutdown(Duration.ofMinutes(2)));
        awaitQuietly(() -> process.shutdownPhase() == ServerProcess.ShutdownPhase.GATE_CLOSED);

        assertFalse(process.shutdownAfterWatchdogDeath(), "the thread counting down runs the rest");
        // Within 20 s: the 120 s countdown was cut short
        awaitQuietly(() -> process.shutdownPhase() == ServerProcess.ShutdownPhase.STOPPED);

        assertEquals(List.of("gate_closed:120:true", "save:14:false:SAVING", "save_ended:false"), recorder.calls);
    }

    @Test
    @Timeout(60)
    public void aDeathWithoutAShutdownRunsTheWholeSequenceOnTheReportThread() throws Exception {
        final Recorder recorder = new Recorder();
        final ServerProcess process = started(recorder);

        assertTrue(process.shutdownAfterWatchdogDeath());

        assertEquals(List.of("save:14:false:SAVING", "save_ended:false"), recorder.calls,
                "no countdown and no tick thread snapshot");
        assertEquals(ServerProcess.ShutdownPhase.STOPPED, process.shutdownPhase());
    }

    @Test
    @Timeout(90)
    public void aDeathDuringTheSaveCutsItToTheDeathBudgetAndReadsTheRestOnTheShutdownThread() throws Exception {
        final Recorder recorder = new Recorder();
        final ServerProcess process = started(recorder);
        recorder.nextSave.set(new CompletableFuture<>());
        final Thread stopper = Thread.ofPlatform().start(process::stop);
        awaitQuietly(() -> process.shutdownPhase() == ServerProcess.ShutdownPhase.SAVING);

        final long died = System.nanoTime();
        assertFalse(process.shutdownAfterWatchdogDeath());
        stopper.join(TimeUnit.SECONDS.toMillis(60));
        final long savedForMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - died);

        assertEquals(4, recorder.calls.size(), recorder.calls.toString());
        assertEquals("gate_closed:0:true", recorder.calls.get(0));
        assertEquals("save:30:true:SAVING", recorder.calls.get(1));
        assertTrue(recorder.calls.get(2).matches("save:1[34]:false:SAVING"), recorder.calls.get(2));
        assertEquals("save_ended:true", recorder.calls.get(3));
        assertTrue(savedForMillis >= 13_000 && savedForMillis < 30_000,
                "the save ends 14 s (halt-after less 1 s) after the death, not 30 s after it started: "
                        + savedForMillis + " ms");
    }

    @Test
    @Timeout(60)
    public void theParticipantIsSetOnceAndBeforeTheStart() throws Exception {
        final Recorder recorder = new Recorder();
        final ServerProcess process = ServerProcess.of(config());
        process.setShutdownParticipant(recorder);
        assertThrows(IllegalStateException.class, () -> process.setShutdownParticipant(recorder));
        recorder.process = process;
        process.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        try {
            assertThrows(IllegalStateException.class, () -> process.setShutdownParticipant(new Recorder()));
        } finally {
            process.stop();
        }
    }

    /**
     * A dead process saves for {@code synaxis.watchdog.halt-after-ms} less 1 s, so that the save ends,
     * and says what it did not save, before the watchdog halts the JVM.
     */
    @Test
    @Timeout(60)
    public void aDeathSaveEndsASecondBeforeTheWatchdogHalts() {
        final Recorder recorder = new Recorder();
        final ServerProcess process = started(recorder, ServerConfig.builder().watchdogHaltAfter(5_000));
        recorder.nextSave.set(new CompletableFuture<>());

        final long died = System.nanoTime();
        assertTrue(process.shutdownAfterWatchdogDeath());
        final long savedForMillis = TimeUnit.NANOSECONDS.toMillis(recorder.saveEndedAt - died);

        assertEquals(Duration.ofSeconds(4), recorder.firstBudget, "halt-after less 1 s");
        assertEquals(List.of("save:4:false:SAVING", "save_ended:true"), recorder.calls);
        assertTrue(savedForMillis >= 4_000 && savedForMillis < 5_000,
                "the save ended before the halt would come: " + savedForMillis + " ms");
    }

    /**
     * The players in play leave the world on their next tick, which a dead tick never runs: kicking
     * does not wait for it, the shutdown tasks follow at once.
     */
    @Test
    @Timeout(60)
    public void kickingDoesNotWaitForADeadTick() throws Exception {
        final Recorder recorder = new Recorder();
        final ServerProcess process = started(recorder);
        spawn(process);
        final CountDownLatch inTheTick = new CountDownLatch(1);
        final CountDownLatch stuck = new CountDownLatch(1);
        final AtomicLong tasksAt = new AtomicLong();
        process.scheduler().buildShutdownTask(() -> {
            tasksAt.set(System.nanoTime());
            stuck.countDown();
        });
        process.scheduler().scheduleNextTick(() -> {
            inTheTick.countDown();
            try {
                stuck.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        });
        try {
            assertTrue(inTheTick.await(10, TimeUnit.SECONDS), "the tick never got stuck");

            assertTrue(process.shutdownAfterWatchdogDeath());

            final long kickingMillis = TimeUnit.NANOSECONDS.toMillis(tasksAt.get() - recorder.saveEndedAt);
            assertTrue(kickingMillis < 1_000, "from the end of the save to the shutdown tasks: " + kickingMillis + " ms");
            assertEquals(ServerProcess.ShutdownPhase.STOPPED, process.shutdownPhase());
        } finally {
            stuck.countDown();
        }
    }

    /** A shutdown task that throws on the shutdown thread: the others run and the process still stops. */
    @Test
    @Timeout(60)
    public void aShutdownTaskThatThrowsOnTheShutdownThreadStopsNeitherTheOthersNorTheProcess() throws Exception {
        final ServerProcess process = ServerProcess.of(config());
        final List<Throwable> reported = new CopyOnWriteArrayList<>();
        process.exception().setExceptionHandler(reported::add);
        // Bound, with its tick threads, but no tick scheduler: the tasks run on the thread of stop()
        process.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        process.dispatcher().start();
        final IllegalStateException failure = new IllegalStateException("the first task failed");
        final AtomicBoolean ran = new AtomicBoolean();
        process.scheduler().buildShutdownTask(() -> {
            throw failure;
        });
        process.scheduler().buildShutdownTask(() -> ran.set(true));

        try {
            assertDoesNotThrow(process::stop);

            assertTrue(ran.get(), "the task after the one that failed ran");
            assertTrue(reported.contains(failure), reported.toString());
            assertEquals(ServerProcess.ShutdownPhase.STOPPED, process.shutdownPhase());
            for (TickThread thread : process.dispatcher().threads()) {
                thread.join(5_000);
                assertFalse(thread.isAlive(), thread.getName() + " was stopped");
            }
        } finally {
            process.dispatcher().shutdown();
        }
    }

    /** The same task between two ticks, on the tick scheduler thread. */
    @Test
    @Timeout(60)
    public void aShutdownTaskThatThrowsBetweenTwoTicksDoesNotStopTheOthers() {
        final Recorder recorder = new Recorder();
        final ServerProcess process = started(recorder);
        final List<Throwable> reported = new CopyOnWriteArrayList<>();
        process.exception().setExceptionHandler(reported::add);
        final IllegalStateException failure = new IllegalStateException("the first task failed");
        final AtomicReference<Thread> ranOn = new AtomicReference<>();
        process.scheduler().buildShutdownTask(() -> {
            throw failure;
        });
        process.scheduler().buildShutdownTask(() -> ranOn.set(Thread.currentThread()));

        process.stop();

        assertInstanceOf(TickSchedulerThread.class, ranOn.get(), "the task after the one that failed ran");
        assertTrue(reported.contains(failure), reported.toString());
    }

    /** A countdown no wait can take is refused up front instead of breaking the sequence midway. */
    @Test
    public void aCountdownLongerThanAWaitCanTakeIsRefused() {
        final ServerProcess process = ServerProcess.of(config());
        assertThrows(IllegalArgumentException.class, () -> process.beginShutdown(ChronoUnit.FOREVER.getDuration()));
        assertEquals(ServerProcess.ShutdownPhase.RUNNING, process.shutdownPhase(), "nothing started");
    }

    private static ServerConfig config() {
        return config(ServerConfig.builder());
    }

    private static ServerConfig config(ServerConfig.Builder builder) {
        return builder.insideTest(true).shutdownOnSignal(false).build();
    }

    private static ServerProcess started(Recorder recorder) {
        return started(recorder, ServerConfig.builder());
    }

    private static ServerProcess started(Recorder recorder, ServerConfig.Builder builder) {
        final ServerProcess process = ServerProcess.of(config(builder));
        recorder.process = process;
        process.setShutdownParticipant(recorder);
        // What MinecraftServer#start does: bind, then tick
        process.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        process.startTicking();
        return process;
    }

    /** A player in play on a started process, through a connection that writes nowhere. */
    private static Player spawn(ServerProcess process) {
        final InstanceContainer instance = process.instance().createInstanceContainer();
        instance.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.STONE));
        // Loaded up front: a test process joins the spawn future on the tick thread
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) instance.loadChunk(x, z).join();
        final SilentConnection connection = new SilentConnection(process);
        final Player player = process.connection().createPlayer(connection, new GameProfile(UUID.randomUUID(), "Stopped"));
        assertNotNull(player);
        player.eventNode().addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(instance);
            event.getPlayer().setRespawnPoint(new Pos(0, 40, 0));
        });
        CompletableFuture.runAsync(() -> {
            process.connection().doConfiguration(player, false);
            process.connection().transitionConfigToPlay(player);
        }, Thread.ofVirtual()::start).join();
        connection.setClientState(ConnectionState.PLAY);
        connection.setServerState(ConnectionState.PLAY);
        awaitQuietly(() -> player.getInstance() != null);
        return player;
    }

    private static void awaitQuietly(BooleanSupplier condition) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0) throw new AssertionError("condition not met in 20 s");
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    private static void send(SocketChannel channel, ConnectionState state, ClientPacket packet) throws IOException {
        final NetworkBuffer framed = PacketWriting.allocateTrimmedPacket(state, packet, null, 0);
        while (!framed.writeChannel(channel)) {
            // A blocking channel writes everything, the loop only documents the contract
        }
    }

    private static ServerPacket read(SocketChannel channel, ServerProcess process) throws Exception {
        final InputStream in = channel.socket().getInputStream();
        final ByteArrayOutputStream frame = new ByteArrayOutputStream();
        int length = 0;
        for (int shift = 0; ; shift += 7) {
            final int b = in.read();
            if (b < 0) throw new AssertionError("closed before an answer");
            frame.write(b);
            length |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
        }
        for (int i = 0; i < length; i++) frame.write(in.read());
        final byte[] bytes = frame.toByteArray();
        final NetworkBuffer buffer = NetworkBuffer.wrap(bytes, 0, bytes.length, process.registries());
        final PacketReading.Result<ServerPacket> result = PacketReading.readServers(buffer, ConnectionState.LOGIN, false);
        if (!(result instanceof PacketReading.Result.Success<ServerPacket> success))
            throw new AssertionError("not a packet: " + bytes.length + " bytes");
        return success.packets().getFirst().packet();
    }

    private static final class SilentConnection extends PlayerConnection {
        private SilentConnection(ServerProcess process) {
            super(process);
        }

        @Override
        public void sendPacket(SendablePacket packet) {
            // Nothing to write to
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("localhost", 25565);
        }
    }
}
