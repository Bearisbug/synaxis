package io.github.bearisbug.synaxis.loadtest;

import com.google.gson.JsonObject;
import io.github.bearisbug.synaxis.monitoring.EventsJFR;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code sample} ({@code docs/DESIGN-PROD.md} {@code API-PROD-025}): {@code ticks.acquire_ms} is the
 * distribution of the acquisition wait each {@code synaxis.ServerTickTime} event carries
 * ({@code API-PROD-016}), in the shape of {@code ticks.overall}.
 */
public class SampleAcquireTest {

    /**
     * The waits are given, as the engine sets them on the event, not measured: 0, 1, 2.5 and 40 ms.
     */
    @Test
    public void theAcquisitionWaitOfEveryTickEventIsDistributed(@TempDir Path directory) throws Exception {
        final Path file = directory.resolve("ticks.jfr");
        final Instant started = Instant.now();
        final long[] acquireNanos = {0, 1_000_000, 2_500_000, 40_000_000};
        try (Recording recording = new Recording()) {
            recording.enable(SampleCommand.TICK_EVENT);
            recording.start();
            for (long nanos : acquireNanos) {
                final EventsJFR.ServerTickMarker tick = EventsJFR.newServerTick();
                tick.begin();
                tick.acquisitionTime(nanos);
                tick.commit();
            }
            recording.stop();
            recording.dump(file);
        }
        final JsonObject ticks = SampleCommand.ticks(file, started, new JsonObject());
        final JsonObject acquire = ticks.getAsJsonObject("acquire_ms");
        assertEquals(4, acquire.get("count").getAsLong(), "one acquisition wait per tick: " + ticks);
        assertEquals(10.875, acquire.get("mean_ms").getAsDouble());
        assertEquals(1.0, acquire.get("p50_ms").getAsDouble(), "the nearest rank median of 4 is the second");
        assertEquals(40.0, acquire.get("p99_ms").getAsDouble());
        assertEquals(40.0, acquire.get("max_ms").getAsDouble());
        assertEquals(0, acquire.get("over_50ms").getAsLong());
    }

    @Test
    public void ticksWithoutAnAcquisitionWaitGiveNull() {
        final Instant started = Instant.parse("2026-09-30T00:00:00Z");
        final SampleCommand.Ticks ticks = new SampleCommand.Ticks(started);
        ticks.record(started, 3.0);
        final JsonObject json = ticks.toJson();
        assertEquals(1, json.get("count").getAsLong());
        assertTrue(json.get("acquire_ms").isJsonNull(), "no tick carried an acquisition wait: " + json);
    }
}
