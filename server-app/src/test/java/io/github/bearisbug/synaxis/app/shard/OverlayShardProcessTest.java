package io.github.bearisbug.synaxis.app.shard;

import io.github.bearisbug.synaxis.synx.OverlayHit;
import io.github.bearisbug.synaxis.synx.OverlayOptions;
import io.github.bearisbug.synaxis.synx.OverlayStore;
import io.github.bearisbug.synaxis.synx.SynxException;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The overlay store of a map shard in a JVM of its own, {@link OverlayShardFixture} ({@code docs/DESIGN-M3.md}
 * §19 「覆盖层崩溃丢失窗口有上界」 and 「正常停机不丢覆盖层改动」 as processes; {@code REQ-M3-011}): killed
 * with SIGKILL the shard keeps every change older than its flush interval, stopped with SIGTERM it
 * keeps every change. The store is opened again on the same directory in this JVM afterwards.
 */
@Timeout(180)
public class OverlayShardProcessTest {

    private static final int FLUSH_INTERVAL_S = 10;
    /**
     * A change is due once it is {@code flush_interval_s} old and is on disk after the next write
     * phase: up to one tick, its encoding and one fsync. The margin covers that on a loaded machine.
     */
    private static final long WRITE_MARGIN_MS = 1_000;
    /** The kill comes this long after the first change: the older half of the changes is due by then, the newer half not. */
    private static final long KILL_AFTER_MS = 20_000;

    private record Change(int chunkX, int chunkZ, long at) {
    }

    /** The fixture JVM, its output collected line by line. */
    private record Fixture(Process process, List<String> output, Thread reader) implements AutoCloseable {

        static Fixture start(Path directory, ShardSettings release, int count, long periodMillis) throws IOException {
            final List<String> command = new ArrayList<>(List.of(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx512m",
                    "-cp", System.getProperty("java.class.path"),
                    "-Dlogback.configurationFile=logback.xml", "-Dsynaxis.app.log=stdout",
                    "-Dsynaxis.map.root=" + release.mapRoot(), "-Dsynaxis.map.build=" + release.mapBuild(),
                    "-Dsynaxis.overlay.flush-interval-s=" + FLUSH_INTERVAL_S));
            // The multi-threaded rounds of CI reach the fixture as they reach this JVM
            for (String name : List.of("synaxis.dispatcher-threads", "synaxis.acquirable-strict", "synaxis.thread-provider")) {
                final String value = System.getProperty(name);
                if (value != null) command.add("-D" + name + "=" + value);
            }
            command.addAll(List.of(OverlayShardFixture.class.getName(), Integer.toString(count), Long.toString(periodMillis)));
            final Process process = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .start();
            final List<String> output = new CopyOnWriteArrayList<>();
            final Thread reader = Thread.ofPlatform().start(() -> {
                try (BufferedReader lines = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = lines.readLine()) != null) output.add(line);
                } catch (IOException _) {
                    // the JVM is gone, what it wrote is in `output`
                }
            });
            return new Fixture(process, output, reader);
        }

        String log() {
            return String.join("\n", output);
        }

        void awaitLine(String text) throws InterruptedException {
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            while (output.stream().noneMatch(line -> line.contains(text))) {
                if (!process.isAlive() || System.nanoTime() > deadline)
                    throw new AssertionError("no line with \"" + text + "\":\n" + log());
                Thread.sleep(20);
            }
        }

        List<Change> changes() {
            final List<Change> changes = new ArrayList<>();
            for (String line : output) {
                if (!line.startsWith(OverlayShardFixture.CHANGED)) continue;
                final String[] parts = line.substring(OverlayShardFixture.CHANGED.length()).split(" ", -1);
                changes.add(new Change(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Long.parseLong(parts[2])));
            }
            return changes;
        }

        void awaitExit() throws InterruptedException {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the fixture JVM exits: " + log());
            reader.join(TimeUnit.SECONDS.toMillis(10));
        }

        @Override
        public void close() {
            process.destroyForcibly();
            try {
                process.waitFor(30, TimeUnit.SECONDS);
                reader.join(TimeUnit.SECONDS.toMillis(10));
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static OverlayStore reopen(Path directory, ShardSettings release) throws IOException, SynxException {
        final SynxManifest manifest = SynxManifest.fromJson(
                Files.readString(release.buildDir().resolve(SynxManifest.FILE_NAME)));
        return OverlayStore.open(directory.resolve("overlay"), manifest, ShardWorld.overlaySettings(manifest, 256),
                new OverlayOptions(16_384, FLUSH_INTERVAL_S));
    }

    private static @Nullable OverlayHit written(OverlayStore store, Change change) {
        final OverlayHit hit = store.find(change.chunkX(), change.chunkZ());
        // One change a chunk: a record of it has generation 1
        return hit != null && hit.generation() >= 1 ? hit : null;
    }

    /**
     * §19 「覆盖层崩溃丢失窗口有上界」: a change every 100 ms from t = 0, SIGKILL at t = 20 s. The change
     * of t = 0 (chunk E of the scenario) is on disk, so is every change more than the flush interval
     * and the write margin older than the kill; the newest ones are not, the kill wrote nothing.
     */
    @Test
    public void aKilledShardKeepsEveryChangeOlderThanItsFlushInterval(@TempDir Path directory) throws Exception {
        final ShardSettings release = MapFixture.bake(directory.resolve("maps"));
        final List<Change> changes;
        final long killedAt;
        try (Fixture fixture = Fixture.start(directory, release, 256, 100)) {
            fixture.awaitLine(OverlayShardFixture.CHANGED);
            final long first = fixture.changes().getFirst().at();
            final long wait = first + KILL_AFTER_MS - System.currentTimeMillis();
            if (wait > 0) Thread.sleep(wait);
            killedAt = System.currentTimeMillis();
            // Through the handle: Process#destroyForcibly would also close the pipe the last lines are still in
            fixture.process().toHandle().destroyForcibly();
            fixture.awaitExit();
            changes = fixture.changes();
            assertFalse(fixture.log().contains("Overlay final flush"), "a SIGKILL runs no final flush: " + fixture.log());
        }

        final OverlayStore store = reopen(directory, release);
        final List<Change> kept = new ArrayList<>(), lost = new ArrayList<>();
        for (Change change : changes) (written(store, change) != null ? kept : lost).add(change);
        final long bound = TimeUnit.SECONDS.toMillis(FLUSH_INTERVAL_S) + WRITE_MARGIN_MS;
        final long oldestLost = lost.stream().mapToLong(change -> killedAt - change.at()).max().orElse(0);
        final long newestKept = kept.stream().mapToLong(change -> killedAt - change.at()).min().orElse(-1);
        System.out.printf("crash window: %d changes, %d kept, %d lost; the oldest lost change was %d ms old at the kill, "
                + "the newest kept one %d ms%n", changes.size(), kept.size(), lost.size(), oldestLost, newestKept);

        assertNotNull(written(store, changes.getFirst()), "chunk E, changed at t = 0 s, is on disk after a kill at t = 20 s");
        for (Change change : changes) {
            if (killedAt - change.at() > bound)
                assertNotNull(written(store, change), change + " was " + (killedAt - change.at()) + " ms old at the kill");
        }
        assertTrue(oldestLost <= bound, "lost a change " + oldestLost + " ms old");
        assertFalse(lost.isEmpty(), "the changes of the last seconds were not written: the kill ended the process");
    }

    /**
     * §19 「正常停机不丢覆盖层改动」 in a process: 100 chunks changed within the last second, then SIGTERM.
     * The final flush writes them all in one batch at the end of a tick, and the store has every one.
     */
    @Test
    public void aStoppedShardWritesTheChangesOfItsLastSecond(@TempDir Path directory) throws Exception {
        final ShardSettings release = MapFixture.bake(directory.resolve("maps"));
        final List<Change> changes;
        final String log;
        try (Fixture fixture = Fixture.start(directory, release, 100, 0)) {
            fixture.awaitLine(OverlayShardFixture.DONE);
            final long stoppedAt = System.currentTimeMillis();
            // SIGTERM through the handle: Process#destroy would also close the pipe the shutdown logs to
            fixture.process().toHandle().destroy();
            fixture.awaitExit();
            changes = fixture.changes();
            log = fixture.log();
            assertEquals(100, changes.size(), log);
            assertTrue(stoppedAt - changes.getFirst().at() < 1_000,
                    "all 100 changed within the last second before the stop: " + (stoppedAt - changes.getFirst().at()) + " ms");
        }

        assertTrue(log.lines().anyMatch(line -> line.contains("Overlay final flush: chunks=100 ")
                && line.contains("left=0") && line.endsWith("by=tick")), log);
        final OverlayStore store = reopen(directory, release);
        for (Change change : changes)
            assertNotNull(written(store, change), change + " is on disk after the stop");
    }
}
