package io.github.bearisbug.synaxis.app.shard;

import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.ShutdownParticipant;
import io.github.bearisbug.synaxis.app.ShutdownSequence;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.synx.OverlayOptions;
import io.github.bearisbug.synaxis.synx.SynxOptions;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * The JVM {@link OverlayShardProcessTest} starts and then kills or stops: a map shard assembled as
 * {@code Main} assembles one in its steps (3) and (5), on the release the {@code synaxis.map.*} keys
 * name, with the overlay store under {@code overlay/} of the working directory, its final flush
 * registered on the real {@link ShutdownSequence}, which is the shutdown participant of the process.
 * The process binds a loopback port and ticks, and a SIGTERM stops it through the engine's shutdown
 * hook, as it stops a shard.
 * <p>
 * Once ticking it loads {@code count} chunks of the map, then changes one block in each, one chunk
 * every {@code period} ms, and prints {@code changed <x> <z> <epoch ms>} after each change and
 * {@link #DONE} after the last. Each chunk is held by a ticket first, as a player near it would hold
 * it, so that no automatic unload writes it before its flush interval is up.
 */
final class OverlayShardFixture {

    static final String READY = "overlay fixture ready";
    static final String CHANGED = "changed ";
    static final String DONE = "overlay fixture done";
    static final int CHANGE_Y = 50;

    private OverlayShardFixture() {
    }

    /** The player store of a shard nobody joined: saved at once. */
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

    /**
     * @param args the number of chunks to change, and the milliseconds between two changes
     */
    public static void main(String[] args) throws InterruptedException {
        final int count = Integer.parseInt(args[0]);
        final long periodMillis = Long.parseLong(args[1]);
        final MinecraftServer server = MinecraftServer.init();
        final ServerProcess process = server.process();
        final ShardWorld world = ShardWorld.prepare(process, ShardSettings.fromSystemProperties(),
                SynxOptions.fromSystemProperties(), OverlayOptions.fromSystemProperties(), null);
        final ShutdownSequence shutdown = new ShutdownSequence(process, new NoPlayers());
        process.setShutdownParticipant(shutdown);
        final InstanceContainer instance = world.start(shutdown, (_, _) -> {
        }, null).instance();
        server.start("127.0.0.1", 0);
        System.out.println(READY);

        final Object owner = new Object();
        for (int i = 0; i < count; i++) {
            instance.addChunkTicket(i % 16, i / 16, owner);
            instance.loadChunk(i % 16, i / 16).join();
        }
        // Every chunk loaded before the first change: the changes follow each other at the period however long a load takes
        for (int i = 0; i < count; i++) {
            final int chunkX = i % 16, chunkZ = i / 16;
            instance.setBlock(chunkX * 16 + 1, CHANGE_Y, chunkZ * 16 + 1, Block.GOLD_BLOCK);
            System.out.println(CHANGED + chunkX + " " + chunkZ + " " + System.currentTimeMillis());
            if (periodMillis > 0) Thread.sleep(periodMillis);
        }
        System.out.println(DONE);
    }
}
