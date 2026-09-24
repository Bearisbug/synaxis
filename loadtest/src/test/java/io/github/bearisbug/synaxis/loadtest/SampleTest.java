package io.github.bearisbug.synaxis.loadtest;

import com.google.gson.JsonObject;
import io.github.bearisbug.synaxis.monitoring.EventsJFR;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code sample} ({@code API-PROD-025}): the ticks come from the engine's own JFR event, the gate
 * and the busy share of each tick thread from the metrics endpoint's text.
 */
public class SampleTest {

    @Test
    public void everyServerTickEventIsCountedAndTheSlowOneIsOver50(@TempDir Path directory) throws Exception {
        final Path file = directory.resolve("ticks.jfr");
        final Instant started = Instant.now();
        try (Recording recording = new Recording()) {
            recording.enable(SampleCommand.TICK_EVENT);
            recording.start();
            for (long millis : new long[]{2, 3, 4, 60}) {
                final EventsJFR.EventMarker tick = EventsJFR.newServerTick();
                tick.begin();
                TimeUnit.MILLISECONDS.sleep(millis);
                tick.commit();
            }
            recording.stop();
            recording.dump(file);
        }
        final JsonObject gc = new JsonObject();
        final JsonObject ticks = SampleCommand.ticks(file, started, gc);
        assertEquals(4, ticks.get("count").getAsLong());
        final JsonObject overall = ticks.getAsJsonObject("overall");
        assertEquals(1, overall.get("over_50ms").getAsLong(), overall.toString());
        assertTrue(overall.get("p99_ms").getAsDouble() >= 60, "the nearest rank P99 of 4 ticks is the slowest");
        assertTrue(overall.get("p50_ms").getAsDouble() < 50);
        assertTrue(ticks.getAsJsonObject("per_minute").has("0"));
        assertTrue(ticks.get("acquire_ms").isJsonNull(), "not exported until audit P3-3");
    }

    @Test
    public void theGateAndTheBusyShareComeFromTheMetricsText() {
        final String first = """
                # TYPE synaxis_tick_duration_seconds summary
                synaxis_tick_duration_seconds_sum{thread="Syn-Tick-0"} 1.0
                synaxis_tick_duration_seconds_sum{thread="Syn-TickScheduler"} 2.0
                synaxis_gate_rejections_total{reason="throttled"} 0.0
                """;
        final String last = """
                synaxis_tick_duration_seconds_sum{thread="Syn-Tick-0"} 3.0
                synaxis_tick_duration_seconds_sum{thread="Syn-TickScheduler"} 6.0
                synaxis_gate_rejections_total{reason="throttled"} 7.0
                synaxis_gate_rejections_total{reason="too_many"} 1.0
                """;
        final Map<String, Double> before = SampleCommand.parse(first);
        final Map<String, Double> after = SampleCommand.parse(last);
        assertEquals(1.0, before.get("synaxis_tick_duration_seconds_sum{thread=\"Syn-Tick-0\"}"));
        final JsonObject gate = SampleCommand.gate(after);
        assertEquals(7.0, gate.get("throttled").getAsDouble());
        assertEquals(1.0, gate.get("too_many").getAsDouble());
        final JsonObject busy = SampleCommand.busy(before, after, 10.0);
        assertEquals(0.2, busy.get("Syn-Tick-0").getAsDouble(), "2 s of ticks in 10 s");
        assertEquals(0.4, busy.get("Syn-TickScheduler").getAsDouble());
    }

    @Test
    public void durationsAndRatesReadAsWritten() {
        assertEquals(1800.0, Args.seconds("30m"));
        assertEquals(600.0, Args.seconds("600s"));
        assertEquals(12.5, Args.seconds("12.5"));
        assertEquals(100.0, new Args(List.of("--ip-rate", "100/s")).rate("ip-rate", 0));
    }
}
