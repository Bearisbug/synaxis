package io.github.bearisbug.synaxis.app.shard;

import com.sun.management.OperatingSystemMXBean;
import io.github.bearisbug.synaxis.ShutdownParticipant;
import io.github.bearisbug.synaxis.app.ShutdownSequence;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.instance.InstanceChunkLoadEvent;
import io.github.bearisbug.synaxis.event.instance.InstanceChunkUnloadEvent;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerPositionPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientTeleportConfirmPacket;
import io.github.bearisbug.synaxis.synx.OverlayOptions;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import io.github.bearisbug.synaxis.synx.SynxOptions;
import io.github.bearisbug.synaxis.testing.Env;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * §19 「单个客户端在 bounds 内扫图的常驻上界」 ({@code docs/DESIGN-M3.md} {@code REQ-M3-029}, §15 资源上限):
 * a measurement with a {@code main}, not a JUnit test, never in a gate. It mounts the release that
 * {@code synaxis.map.root} and {@code synaxis.map.build} name as {@code MapShardPlayerAuthorityTest#mount}
 * does, outside the directory mode, with what {@code ShardMain} sets for a map shard: view distance 12,
 * simulation distance 4, unload grace 100, {@code world_writable = false}. One test player, once the entity
 * tracker has it, then sweeps the bounds for {@code <seconds>}: rows 27 chunks apart, back and forth, each
 * position packet 64 blocks along one axis, 5 or 6 packets a tick, 107 a second, the cap of the inbound gate;
 * a packet whose target chunk is not loaded waits for it. Run it on the test runtime classpath:
 * {@code java @server-app/build/light-fixture.args -Xms4G -Xmx4G -Dsynaxis.map.root=<O>/maps12/synth
 * -Dsynaxis.map.build=synth-09b42ebb5dc6-p776 io.github.bearisbug.synaxis.app.shard.ShardSweepProbe 120}.
 * <p>
 * One line of readings goes to the standard output: the packet rate, the resident chunks at the start and
 * at their peak, the loads and unloads a second, the resident bytes a chunk (the live heap after three
 * collections before the sweep and after it, the ticks stopped, over the chunks between them), the process
 * CPU microseconds a load, the P99 of the tick, and the verdict of {@code view_bounds} chunks × bytes a chunk
 * ≤ 1 GiB. The exit code is 1 when an assertion fails: every move inside the bounds taken (no turn back),
 * the resident chunks never over {@code view_bounds}, and no chunk asked for outside it.
 */
public final class ShardSweepProbe {
    private static final int VIEW = 12;
    private static final int ROW_CHUNKS = 27;
    private static final int STEP = 64;
    private static final long TICK_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final long BUDGET_BYTES = 1L << 30;

    private ShardSweepProbe() {
    }

    private static final class NoPlayers implements ShutdownParticipant {
        @Override
        public void onGateClosed(Duration countdown) {
        }

        @Override
        public CompletableFuture<?> saveAll(Duration budget, boolean tickAlive) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onSaveEnded(boolean timedOut) {
        }
    }

    public static void main(String[] args) throws Exception {
        final long seconds = Long.parseLong(args[0]);
        final ShardSettings settings = ShardSettings.fromSystemProperties();
        final Path overlay = Files.createTempDirectory("shard-sweep-overlay");
        final Env env = Env.create(config -> config.chunkViewDistance(VIEW));
        int exit = 1;
        try {
            exit = sweep(env, settings, overlay, seconds);
        } finally {
            env.process().stop();
            try (Stream<Path> paths = Files.walk(overlay)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.exit(exit);
    }

    private static int sweep(Env env, ShardSettings settings, Path overlay, long seconds) throws Exception {
        final ShardWorld world = ShardWorld.prepare(env.process(), settings,
                new SynxOptions(false, SynxOptions.DEFAULT_SECTION_CACHE_BYTES, Math.max(1,
                        Runtime.getRuntime().availableProcessors() / 4), false), new OverlayOptions(16_384, 10), null);
        final ShardWorld.Started started = world.start(new ShutdownSequence(env.process(), new NoPlayers()), (_, _) -> {
        }, null, overlay);
        final InstanceContainer instance = started.instance();
        final SynxManifest.Bounds bounds = SynxManifest.fromJson(Files.readString(
                settings.buildDir().resolve(SynxManifest.FILE_NAME))).bounds();
        final long viewChunks = (long) (bounds.maxX() - bounds.minX() + 1 + 34) * (bounds.maxZ() - bounds.minZ() + 1 + 34);
        final AtomicLong loads = new AtomicLong(), unloads = new AtomicLong();
        env.process().eventHandler().addListener(InstanceChunkLoadEvent.class, _ -> loads.incrementAndGet())
                .addListener(InstanceChunkUnloadEvent.class, _ -> unloads.incrementAndGet());

        final Pos spawn = started.spawn();
        final Player player = env.createPlayer(instance, spawn);
        while (instance.getEntityTracker().getEntityByUuid(player.getUuid()) == null) env.tick();
        for (int i = 0; i < 300 && instance.getChunks().size() < (2 * VIEW + 3) * (2 * VIEW + 3) - 1; i++) {
            env.tick();
            TimeUnit.MILLISECONDS.sleep(10);
        }
        player.addPacketToQueue(new ClientTeleportConfirmPacket(player.getLastSentTeleportId()));
        env.tick();

        final int chunksBefore = instance.getChunks().size();
        final long heapBefore = liveHeap();
        final OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        final long cpuBefore = os.getProcessCpuTime();
        final long loadsBefore = loads.get(), unloadsBefore = unloads.get();

        // The sweep: along x to the far side of the bounds, a row of 27 chunks along z, back along x
        final int minX = bounds.minX() * 16 + 8, maxX = bounds.maxX() * 16 + 8;
        final int minZ = bounds.minZ() * 16 + 8, maxZ = bounds.maxZ() * 16 + 8;
        double x = spawn.x(), z = spawn.z();
        int direction = 1, rowDirection = 1;
        double rowTarget = z;
        long packets = 0, turnedBack = 0, ticks = 0;
        int chunksPeak = chunksBefore;
        final long[] tickNanos = new long[(int) (seconds * 20 + 100)];
        final long start = System.nanoTime();
        long nextTick = start;
        while (System.nanoTime() - start < TimeUnit.SECONDS.toNanos(seconds)) {
            final int perTick = ticks % 20 < 7 ? 6 : 5;
            for (int i = 0; i < perTick; i++) {
                double nextX = x, nextZ = z;
                if (z != rowTarget) {
                    nextZ = z + Math.signum(rowTarget - z) * Math.min(STEP, Math.abs(rowTarget - z));
                } else {
                    nextX = x + direction * STEP;
                    if (nextX > maxX || nextX < minX) {
                        nextX = x;
                        direction = -direction;
                        double row = z + rowDirection * ROW_CHUNKS * 16;
                        if (row > maxZ || row < minZ) {
                            rowDirection = -rowDirection;
                            row = z + rowDirection * ROW_CHUNKS * 16;
                        }
                        rowTarget = row;
                        nextZ = z + Math.signum(rowTarget - z) * Math.min(STEP, Math.abs(rowTarget - z));
                    }
                }
                if (!instance.isChunkLoaded(((int) Math.floor(nextX)) >> 4, ((int) Math.floor(nextZ)) >> 4)) break;
                player.addPacketToQueue(new ClientPlayerPositionPacket(new Vec(nextX, spawn.y(), nextZ), (byte) 1));
                packets++;
                x = nextX;
                z = nextZ;
            }
            final long tickStart = System.nanoTime();
            env.tick();
            if (ticks < tickNanos.length) tickNanos[(int) ticks] = System.nanoTime() - tickStart;
            ticks++;
            if (player.getPosition().x() != x || player.getPosition().z() != z) {
                turnedBack++;
                x = player.getPosition().x();
                z = player.getPosition().z();
                rowTarget = z;
                player.addPacketToQueue(new ClientTeleportConfirmPacket(player.getLastSentTeleportId()));
            }
            chunksPeak = Math.max(chunksPeak, instance.getChunks().size());
            nextTick += TICK_NANOS;
            final long sleep = nextTick - System.nanoTime();
            if (sleep > 0) TimeUnit.NANOSECONDS.sleep(sleep);
        }
        final double elapsed = (System.nanoTime() - start) / 1e9;
        final long cpu = os.getProcessCpuTime() - cpuBefore;
        final long sweptLoads = loads.get() - loadsBefore, sweptUnloads = unloads.get() - unloadsBefore;
        final int chunksAfter = instance.getChunks().size();
        final long heapAfter = liveHeap();
        final long bytesPerChunk = chunksAfter > chunksBefore ? (heapAfter - heapBefore) / (chunksAfter - chunksBefore) : -1;
        final long[] sorted = Arrays.copyOf(tickNanos, (int) Math.min(ticks, tickNanos.length));
        Arrays.sort(sorted);
        final double tickP99 = sorted.length == 0 ? 0 : sorted[(int) Math.ceil(sorted.length * 0.99) - 1] / 1e6;
        final long voidChunks = ((ShardInstance) instance).voidChunks();
        final boolean fits = bytesPerChunk >= 0 && viewChunks * bytesPerChunk <= BUDGET_BYTES;
        System.out.println(String.format(Locale.ROOT, "sweep: seconds=%.1f packets=%d packets_per_s=%.1f turned_back=%d"
                        + " chunks_start=%d chunks_peak=%d chunks_end=%d view_chunks=%d loads_per_s=%.1f unloads_per_s=%.1f"
                        + " heap_before=%d heap_after=%d bytes_per_chunk=%d cpu_us_per_load=%.1f tick_p99_ms=%.2f"
                        + " void_chunks=%d budget_bytes=%d verdict=%s", elapsed, packets, packets / elapsed, turnedBack,
                chunksBefore, chunksPeak, chunksAfter, viewChunks, sweptLoads / elapsed, sweptUnloads / elapsed, heapBefore,
                heapAfter, bytesPerChunk, sweptLoads == 0 ? 0 : cpu / 1e3 / sweptLoads, tickP99, voidChunks, BUDGET_BYTES,
                fits ? "pass" : "fail"));
        final boolean asserted = turnedBack == 0 && chunksPeak <= viewChunks && voidChunks == 0;
        if (!asserted) {
            System.out.println("sweep: an assertion failed: turned_back=" + turnedBack + " chunks_peak=" + chunksPeak
                    + " (at most " + viewChunks + ") void_chunks=" + voidChunks);
        }
        return asserted ? 0 : 1;
    }

    /** The heap in use after three collections. */
    private static long liveHeap() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            TimeUnit.MILLISECONDS.sleep(100);
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
}
