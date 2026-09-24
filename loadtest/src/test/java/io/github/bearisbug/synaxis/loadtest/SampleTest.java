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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code sample} ({@code API-PROD-025}): the ticks come from the engine's own JFR event, the gate
 * and the busy share of each tick thread from the metrics endpoint's text.
 */
public class SampleTest {

    @Test
    public void everyServerTickEventIsCountedAndNoOtherEvent(@TempDir Path directory) throws Exception {
        final Path file = directory.resolve("ticks.jfr");
        final Instant started = Instant.now();
        try (Recording recording = new Recording()) {
            recording.enable(SampleCommand.TICK_EVENT);
            recording.enable(EventsJFR.SERVER_PING);
            recording.start();
            for (int i = 0; i < 4; i++) {
                final EventsJFR.EventMarker tick = EventsJFR.newServerTick();
                tick.begin();
                tick.commit();
                final EventsJFR.EventMarker ping = EventsJFR.newServerPing("127.0.0.1");
                ping.begin();
                ping.commit();
            }
            recording.stop();
            recording.dump(file);
        }
        final JsonObject ticks = SampleCommand.ticks(file, started, new JsonObject());
        assertEquals(4, ticks.get("count").getAsLong(), "4 ticks and 4 pings recorded: " + ticks);
        assertEquals(4, ticks.getAsJsonObject("per_minute").getAsJsonObject("0").get("count").getAsLong());
        assertTrue(ticks.get("acquire_ms").isJsonNull(), "not exported until audit P3-3");
    }

    /**
     * The durations are given, not measured: a JFR duration is read from the JVM's tick counter, on
     * x86 with an invariant TSC the counter divided by the frequency in the CPU's brand string, which
     * need not be the frequency the counter runs at, so a slept 60 ms can read as less.
     */
    @Test
    public void aTickIsOver50OnlyWhenItsDurationIsAbove50() {
        final Instant started = Instant.parse("2026-09-24T00:00:00Z");
        final SampleCommand.Ticks ticks = new SampleCommand.Ticks(started);
        ticks.record(started.minusSeconds(61), 0.0);
        ticks.record(started, 0.5);
        ticks.record(started.plusSeconds(1), 1.0);
        ticks.record(started.plusSeconds(2), 50.0);
        ticks.record(started.plusMillis(59_999), 50.001);
        ticks.record(started.plusSeconds(60), 60.0);
        final JsonObject json = ticks.toJson();
        assertEquals(6, json.get("count").getAsLong());
        final JsonObject overall = json.getAsJsonObject("overall");
        assertEquals(2, overall.get("over_50ms").getAsLong(), "50.001 and 60 are over 50; 0, 0.5, 1 and 50 are not: " + overall);
        assertEquals(60.0, overall.get("p99_ms").getAsDouble(), "the nearest rank P99 of 6 ticks is the slowest");
        assertEquals(1.0, overall.get("p50_ms").getAsDouble());
        final JsonObject perMinute = json.getAsJsonObject("per_minute");
        assertEquals(List.of("0", "1"), List.copyOf(perMinute.keySet()), "a tick before the start counts in minute 0: " + perMinute);
        assertEquals(5, perMinute.getAsJsonObject("0").get("count").getAsLong());
        assertEquals(1, perMinute.getAsJsonObject("0").get("over_50ms").getAsLong());
        assertEquals(1, perMinute.getAsJsonObject("1").get("over_50ms").getAsLong());
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
