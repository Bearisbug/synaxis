package io.github.bearisbug.synaxis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the shutdown hook does when a {@code beginShutdown} already holds the shutdown
 * ({@code docs/DESIGN.md} {@code API-003}, §11.2; {@code docs/DESIGN-PROD.md} §11.4
 * {@code gate_closed → saving} on a signal): the JVM halts once the hook returns, whatever thread
 * still runs, so the hook cuts the countdown and returns only once that sequence is over.
 */
public class ShutdownSignalHookTest {

    @Test
    @Timeout(60)
    public void aSignalWhileACountdownRunsCutsItAndWaitsForTheSequence() throws Exception {
        final ShutdownClaimTest.Recorder recorder = new ShutdownClaimTest.Recorder();
        final ServerProcessImpl process = (ServerProcessImpl) ShutdownClaimTest.started(recorder);

        assertTrue(process.beginShutdown(Duration.ofSeconds(30)));
        recorder.gateClosedOn.get(10, TimeUnit.SECONDS);
        final long start = System.nanoTime();
        process.stopOnSignal();
        final long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals(ServerProcess.ShutdownPhase.STOPPED, process.shutdownPhase(),
                "the hook returned before the sequence it did not start was over");
        assertTrue(tookMillis < 10_000, "the signal cut the 30 s countdown: " + tookMillis + " ms");
        assertEquals(List.of("gate_closed:30", "save", "save_ended:false"), recorder.calls);
    }
}
