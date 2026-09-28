package io.github.bearisbug.synaxis.app.shard;

import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.ShutdownParticipant;
import io.github.bearisbug.synaxis.app.ShutdownSequence;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.GameMode;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.event.player.PlayerPacketOutEvent;
import io.github.bearisbug.synaxis.event.player.PlayerPluginMessageEvent;
import io.github.bearisbug.synaxis.event.player.PlayerSpawnEvent;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.light.LightResult;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.network.packet.server.play.UpdateLightPacket;
import io.github.bearisbug.synaxis.synx.OverlayOptions;
import io.github.bearisbug.synaxis.synx.RelightStats;
import io.github.bearisbug.synaxis.synx.SynxChunkLoader;
import io.github.bearisbug.synaxis.synx.SynxOptions;
import io.github.bearisbug.synaxis.synx.SynxRelight;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The light fixture of M3-D ({@code docs/DESIGN-M3.md} §26 step 4, §19 the container entries): a map
 * shard on the {@link LightMapFixture} map, assembled as {@code Main} assembles one and with the
 * defaults {@code ShardMain} writes, that the real client of the headless container joins. It binds
 * {@code 0.0.0.0:<port>} and prints {@value #READY}.
 * <p>
 * The test channel {@value #CHANNEL} takes one line of UTF-8 per request and answers each with one
 * message: {@code sites} the named places, {@code tp x y z}, {@code place x y z <block>} and
 * {@code break x y z} (on the player's tick thread when the chunk is loaded, otherwise loaded under a
 * ticket first and done in the scheduling phase of the next tick, the thread premise of
 * {@code ADR-M3-019}), {@code idle} whether a recomputation is pending, {@code truth cx cz} the
 * server's light of a chunk ({@link LightTruth} over its 3×3, loaded, on a thread of its own), 16
 * sections of sky then 16 of block light, {@code sent} the light packets sent to the player per chunk,
 * {@code age} the world age and {@code stats} the counters of the runtime light.
 */
final class LightShardFixture {
    static final String READY = "light fixture ready";
    static final String CHANNEL = "synaxis_test:light";

    /** The center chunk of the map, where players spawn. */
    private static final int SPAWN_CHUNK = 16;
    private static final int FLOOR = LightMapFixture.GRASS + 1;

    /** The named places of {@code sites}: name to block. */
    static final Map<String, int[]> SITES = new LinkedHashMap<>();

    static {
        final int s = SPAWN_CHUNK * 16;
        SITES.put("spawn", new int[]{s + 8, FLOOR, s + 8});
        SITES.put("center", new int[]{s + 8, FLOOR, s + 2});
        SITES.put("edge", new int[]{s + 15, FLOOR, s + 6});
        SITES.put("corner", new int[]{s + 15, FLOOR, s + 15});
        SITES.put("cave", new int[]{s + LightMapFixture.ROOM_MIN, LightMapFixture.ROOM_BOTTOM, s + LightMapFixture.ROOM_MIN});
        SITES.put("own-center", new int[]{s + 5, FLOOR, s + 11});
        SITES.put("own-edge", new int[]{s, FLOOR, s + 4});
        SITES.put("ring-edge", new int[]{480, FLOOR, 264});
        SITES.put("seam-source", new int[]{480, FLOOR, 194});
        SITES.put("seam", new int[]{479, FLOOR, 191});
        SITES.put("far", new int[]{28 * 16 + 8, FLOOR, 27 * 16 + 8});
        SITES.put("fresh", new int[]{5 * 16 + 8, FLOOR, 5 * 16 + 8});
        SITES.put("shaft-cap", new int[]{s + LightMapFixture.SHAFT, LightMapFixture.GRASS, s + LightMapFixture.SHAFT});
        SITES.put("top", new int[]{20 * 16 + 9, 250, 20 * 16 + 8});
        SITES.put("bottom", new int[]{10 * 16 + 6, 1, 10 * 16 + 5});
        SITES.put("flicker", new int[]{s + 10, FLOOR, s + 3});
    }

    private LightShardFixture() {
    }

    /** The player store of a shard nobody is saved in. */
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
     * @param args the port, the working directory the map is baked into and the overlay store kept in, and
     *             {@code drive} for {@link #drive}
     */
    public static void main(String[] args) {
        final int port = Integer.parseInt(args[0]);
        final Path workDir = Path.of(args[1]);
        // What ShardMain writes, where the command line said nothing
        final int cores = Runtime.getRuntime().availableProcessors();
        setIfAbsent("synaxis.thread-provider", "grid");
        setIfAbsent("synaxis.dispatcher-threads", Integer.toString(Math.max(1, cores - 2)));
        setIfAbsent("synaxis.chunk-view-distance", "12");
        setIfAbsent("synaxis.chunk-queue.max-per-tick", "4");
        setIfAbsent("synaxis.max-visible-entities-per-player", "100");

        final ShardSettings settings = LightMapFixture.bake(workDir.resolve("maps"), Math.max(1, cores / 2));
        final MinecraftServer server = MinecraftServer.init();
        final ServerProcess process = server.process();
        final ShardWorld world = ShardWorld.prepare(process, settings, SynxOptions.fromSystemProperties(),
                OverlayOptions.fromSystemProperties(), null);
        final ShutdownSequence shutdown = new ShutdownSequence(process, new NoPlayers());
        process.setShutdownParticipant(shutdown);
        final Counters counters = new Counters();
        final ShardWorld.Started started = world.start(shutdown, counters::event, null, workDir.resolve("overlay"));
        final InstanceContainer instance = started.instance();
        final Pos spawn = started.spawn();

        process.eventHandler().addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(instance);
            event.getPlayer().setRespawnPoint(spawn);
        });
        process.eventHandler().addListener(PlayerSpawnEvent.class, event -> {
            if (!event.isFirstSpawn()) return;
            final Player player = event.getPlayer();
            player.setGameMode(GameMode.CREATIVE);
            player.getInventory().setItemStack(0, ItemStack.of(Material.TORCH));
            player.getInventory().setItemStack(1, ItemStack.of(Material.STONE));
        });
        process.eventHandler().addListener(PlayerPacketOutEvent.class, event -> {
            if (event.getPacket() instanceof UpdateLightPacket light) counters.lightSent(event.getPlayer(), light);
        });
        final Channel channel = new Channel(process, instance, started.relight(), counters);
        process.eventHandler().addListener(PlayerPluginMessageEvent.class, event -> {
            if (event.getIdentifier().equals(CHANNEL)) channel.handle(event.getPlayer(), event.getMessageString().trim());
        });

        server.start("0.0.0.0", port);
        System.out.println(READY);
        if (args.length > 2 && args[2].equals("drive")) drive(process, instance, started.relight(), workDir.resolve("drive"));
    }

    /**
     * Without a client: once the file {@code trigger} exists, 60 changes of a torch around the spawn,
     * one every 5 ticks, each in the scheduling phase of a tick, as a flag applies; then the counters
     * once nothing is pending. For a JFR recording of where the light is computed when no container
     * can run; it replaces none of the cases of the client.
     */
    private static void drive(ServerProcess process, InstanceContainer instance, SynxRelight relight, Path trigger) {
        final Object tickets = new Object();
        final int spawn = SPAWN_CHUNK * 16;
        for (int x = SPAWN_CHUNK - 5; x <= SPAWN_CHUNK + 5; x++) {
            for (int z = SPAWN_CHUNK - 5; z <= SPAWN_CHUNK + 5; z++) {
                instance.addChunkTicket(x, z, tickets);
                instance.loadChunk(x, z).join();
            }
        }
        try {
            while (!Files.exists(trigger)) Thread.sleep(200);
            for (int i = 0; i < 60; i++) {
                final int x = spawn - 40 + (i % 10) * 9, z = spawn - 40 + (i / 10) * 13;
                final Block block = (i / 10) % 2 == 0 ? Block.TORCH : Block.GLOWSTONE;
                process.scheduler().scheduleNextTick(() -> instance.setBlock(x, FLOOR, z, block));
                Thread.sleep(250);
            }
            RelightStats stats = relight.stats();
            double installMax = stats.installMsMax();
            while (stats.pending() > 0) {
                Thread.sleep(200);
                stats = relight.stats();
                installMax = Math.max(installMax, stats.installMsMax());
            }
            System.out.println("light fixture drive done: " + stats + " install_ms_max_seen=" + installMax);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) System.setProperty(key, value);
    }

    /** What the fixture counts: the light packets per player and chunk, and the most of the light's windows. */
    private static final class Counters {
        final Map<Player, Map<String, Integer>> sent = new ConcurrentHashMap<>();
        final AtomicReference<Double> installMsMax = new AtomicReference<>(0.0);

        void lightSent(Player player, UpdateLightPacket packet) {
            sent.computeIfAbsent(player, _ -> new ConcurrentHashMap<>()).merge(packet.chunkX() + " " + packet.chunkZ(), 1, Integer::sum);
        }

        void event(String name, Map<String, ?> attributes) {
            if (!name.equals("chunk_stream_stats")) return;
            if (attributes.get("relight_install_ms_max") instanceof Number max) installMsMax.accumulateAndGet(max.doubleValue(), Math::max);
        }

        void read(RelightStats stats) {
            installMsMax.accumulateAndGet(stats.installMsMax(), Math::max);
        }
    }

    /** The requests of the test channel. */
    private static final class Channel {
        private final ServerProcess process;
        private final InstanceContainer instance;
        private final SynxRelight relight;
        private final Counters counters;
        private final Object tickets = new Object();
        private final ExecutorService truths = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon()
                .name("light-truth").factory());

        Channel(ServerProcess process, InstanceContainer instance, SynxRelight relight, Counters counters) {
            this.process = process;
            this.instance = instance;
            this.relight = relight;
            this.counters = counters;
        }

        void handle(Player player, String request) {
            final String[] parts = request.split(" ");
            try {
                switch (parts[0]) {
                    case "sites" -> {
                        final StringBuilder out = new StringBuilder();
                        SITES.forEach((name, at) -> out.append(name).append(' ').append(at[0]).append(' ').append(at[1])
                                .append(' ').append(at[2]).append('\n'));
                        reply(player, out.toString());
                    }
                    case "tp" -> {
                        var _ = player.teleport(new Pos(Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                                Double.parseDouble(parts[3]))).thenRun(() -> reply(player, "ok"));
                    }
                    case "place" -> set(player, parts, Objects.requireNonNull(Block.fromKey(parts[4]), parts[4]));
                    case "break" -> set(player, parts, Block.AIR);
                    case "idle" -> {
                        final RelightStats stats = relight.stats();
                        counters.read(stats);
                        reply(player, "pending=" + stats.pending());
                    }
                    case "age" -> reply(player, Long.toString(instance.getWorldAge()));
                    case "truth" -> {
                        final int chunkX = Integer.parseInt(parts[1]), chunkZ = Integer.parseInt(parts[2]);
                        truths.execute(() -> reply(player, truth(chunkX, chunkZ)));
                    }
                    case "sent" -> {
                        final StringBuilder out = new StringBuilder();
                        counters.sent.getOrDefault(player, Map.of()).forEach((chunk, count) -> out.append(chunk).append(' ')
                                .append(count).append('\n'));
                        reply(player, out.toString());
                    }
                    case "stats" -> {
                        final RelightStats stats = relight.stats();
                        counters.read(stats);
                        reply(player, stats + " install_ms_max_seen=" + counters.installMsMax.get());
                    }
                    default -> reply(player, "unknown " + request);
                }
            } catch (RuntimeException e) {
                reply(player, "error " + e);
            }
        }

        /**
         * A block set as a player's change is: on this player's tick thread if its chunk is loaded;
         * otherwise under a ticket of the fixture, which stays, and in the scheduling phase of the tick
         * after the chunk loaded.
         */
        private void set(Player player, String[] parts, Block block) {
            final int x = Integer.parseInt(parts[1]), y = Integer.parseInt(parts[2]), z = Integer.parseInt(parts[3]);
            final int chunkX = x >> 4, chunkZ = z >> 4;
            if (instance.isChunkLoaded(chunkX, chunkZ)) {
                instance.setBlock(x, y, z, block);
                reply(player, "ok " + instance.getWorldAge());
                return;
            }
            instance.addChunkTicket(chunkX, chunkZ, tickets);
            var _ = instance.loadChunk(chunkX, chunkZ).thenRun(() -> process.scheduler().scheduleNextTick(() -> {
                instance.setBlock(x, y, z, block);
                reply(player, "ok " + instance.getWorldAge());
            }));
        }

        /** The 3×3 in view loaded under the fixture's tickets, then {@link LightTruth}: sky then block, 16 sections each. */
        private byte[] truth(int chunkX, int chunkZ) {
            for (int x = chunkX - 1; x <= chunkX + 1; x++) {
                for (int z = chunkZ - 1; z <= chunkZ + 1; z++) {
                    if (x < LightMapFixture.BOUNDS.minX() - SynxChunkLoader.VIEW_MARGIN
                            || x > LightMapFixture.BOUNDS.maxX() + SynxChunkLoader.VIEW_MARGIN
                            || z < LightMapFixture.BOUNDS.minZ() - SynxChunkLoader.VIEW_MARGIN
                            || z > LightMapFixture.BOUNDS.maxZ() + SynxChunkLoader.VIEW_MARGIN) continue;
                    instance.addChunkTicket(x, z, tickets);
                    instance.loadChunk(x, z).join();
                }
            }
            final LightResult result = LightTruth.of(instance, chunkX, chunkZ);
            final ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 2 * 2048);
            for (int section = 0; section < 16; section++) out.writeBytes(result.sky(chunkX, section, chunkZ));
            for (int section = 0; section < 16; section++) out.writeBytes(result.block(chunkX, section, chunkZ));
            return out.toByteArray();
        }

        private static void reply(Player player, String text) {
            reply(player, text.getBytes(StandardCharsets.UTF_8));
        }

        private static void reply(Player player, byte[] bytes) {
            player.sendPluginMessage(CHANNEL, bytes);
        }
    }
}
