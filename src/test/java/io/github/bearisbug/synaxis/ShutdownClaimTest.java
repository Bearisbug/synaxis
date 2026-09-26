package io.github.bearisbug.synaxis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who started the shutdown ({@code docs/DESIGN.md} {@code API-003}, §11.2): only the call that sets
 * {@code stopping} gets true, every later one gets false at once and leaves the countdown alone,
 * {@code beginShutdown} never waits for the sequence, and {@code stop()} off a tick thread still
 * runs it to the end on the calling thread.
 */
public class ShutdownClaimTest {

    /** Records the countdowns the engine hands over and the thread the gate closes on. */
    private static final class Recorder implements ShutdownParticipant {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final CompletableFuture<Thread> gateClosedOn = new CompletableFuture<>();

        @Override
        public void onGateClosed(Duration countdown) {
            calls.add("gate_closed:" + countdown.toSeconds());
            gateClosedOn.complete(Thread.currentThread());
        }

        @Override
        public CompletableFuture<?> saveAll(Duration budget, boolean tickAlive) {
            calls.add("save");
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onSaveEnded(boolean timedOut) {
            calls.add("save_ended:" + timedOut);
        }
    }

    @Test
    @Timeout(60)
    public void onlyTheFirstCallStartsTheShutdownAndBeginShutdownNeverWaits() throws Exception {
        final Recorder recorder = new Recorder();
        final ServerProcess process = started(recorder);

        final long start = System.nanoTime();
        assertTrue(process.beginShutdown(Duration.ofSeconds(2)), "the first call starts the shutdown");
        assertTrue(process.isAlive(), "beginShutdown returned before the process stopped");
        assertFalse(process.beginShutdown(Duration.ZERO), "a second beginShutdown did not start it");
        assertFalse(process.stop(), "stop() after it did not start it either, and returned at once");
        assertTrue(process.isAlive(), "the countdown of the first call still runs");

        final Thread sequence = recorder.gateClosedOn.get(10, TimeUnit.SECONDS);
        assertEquals("Syn-Shutdown", sequence.getName());
        assertFalse(sequence.isDaemon(), "the JVM stays up until the sequence is over");
        sequence.join(TimeUnit.SECONDS.toMillis(20));
        assertEquals(ServerProcess.ShutdownPhase.STOPPED, process.shutdownPhase());
        final long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(tookMillis >= 2_000, "the 0 s of the later calls did not cut the 2 s countdown: " + tookMillis + " ms");
        assertEquals(List.of("gate_closed:2", "save", "save_ended:false"), recorder.calls);
        assertFalse(process.stop(), "a stopped process is not stopped again");
    }

    @Test
    @Timeout(60)
    public void stopOffATickThreadRunsTheSequenceHereAndSaysItStartedIt() {
        final Recorder recorder = new Recorder();
        final ServerProcess process = started(recorder);

        assertThrows(IllegalArgumentException.class, () -> process.beginShutdown(Duration.ofSeconds(-1)));
        assertEquals(ServerProcess.ShutdownPhase.RUNNING, process.shutdownPhase(), "a negative countdown starts nothing");

        assertTrue(process.stop());
        assertEquals(ServerProcess.ShutdownPhase.STOPPED, process.shutdownPhase(), "stop() returned once stopped");
        assertEquals(Thread.currentThread(), recorder.gateClosedOn.getNow(null), "the sequence ran on this thread");
        assertFalse(process.beginShutdown(Duration.ZERO));
        assertEquals(List.of("gate_closed:0", "save", "save_ended:false"), recorder.calls);
    }

    private static ServerProcess started(Recorder recorder) {
        final ServerProcess process = ServerProcess.of(ServerConfig.builder().insideTest(true).shutdownOnSignal(false).build());
        process.setShutdownParticipant(recorder);
        // What MinecraftServer#start does: bind, then the tick threads and the ticker
        process.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        process.dispatcher().start();
        new TickSchedulerThread(process, TimeUnit.MILLISECONDS.toNanos(50), process.config().maxTickCatchUp()).start();
        return process;
    }
}
