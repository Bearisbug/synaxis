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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

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
        final Thread stopper = Thread.ofPlatform().start(() -> process.beginShutdown(Duration.ofSeconds(3)));
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
        stopper.join();
        assertEquals(List.of("gate_closed:3:true", "save:30:true:SAVING", "save_ended:false"), recorder.calls);
    }

    @Test
    @Timeout(60)
    public void aDeathDuringTheCountdownSkipsToADeadSave() throws Exception {
        final Recorder recorder = new Recorder();
        final ServerProcess process = started(recorder);
        final Thread stopper = Thread.ofPlatform().start(() -> process.beginShutdown(Duration.ofMinutes(2)));
        awaitQuietly(() -> process.shutdownPhase() == ServerProcess.ShutdownPhase.GATE_CLOSED);

        assertFalse(process.shutdownAfterWatchdogDeath(), "the thread counting down runs the rest");
        stopper.join(TimeUnit.SECONDS.toMillis(30));

        assertFalse(stopper.isAlive(), "the 120 s countdown was cut short");
        assertEquals(List.of("gate_closed:120:true", "save:15:false:SAVING", "save_ended:false"), recorder.calls);
    }

    @Test
    @Timeout(60)
    public void aDeathWithoutAShutdownRunsTheWholeSequenceOnTheReportThread() throws Exception {
        final Recorder recorder = new Recorder();
        final ServerProcess process = started(recorder);

        assertTrue(process.shutdownAfterWatchdogDeath());

        assertEquals(List.of("save:15:false:SAVING", "save_ended:false"), recorder.calls,
                "no countdown and no tick thread snapshot");
        assertEquals(ServerProcess.ShutdownPhase.STOPPED, process.shutdownPhase());
    }

    @Test
    @Timeout(90)
    public void aDeathDuringTheSaveCutsItTo15SecondsAndReadsTheRestOnTheShutdownThread() throws Exception {
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
        assertTrue(recorder.calls.get(2).matches("save:1[45]:false:SAVING"), recorder.calls.get(2));
        assertEquals("save_ended:true", recorder.calls.get(3));
        assertTrue(savedForMillis >= 14_000 && savedForMillis < 30_000,
                "the save ends 15 s after the death, not 30 s after it started: " + savedForMillis + " ms");
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

    private static ServerConfig config() {
        return ServerConfig.builder().insideTest(true).shutdownOnSignal(false).build();
    }

    private static ServerProcess started(Recorder recorder) {
        final ServerProcess process = ServerProcess.of(config());
        recorder.process = process;
        process.setShutdownParticipant(recorder);
        // What MinecraftServer#start does: bind, then the tick threads and the ticker
        process.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        process.dispatcher().start();
        new TickSchedulerThread(process, TimeUnit.MILLISECONDS.toNanos(50), process.config().maxTickCatchUp()).start();
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
