package io.github.bearisbug.synaxis.game.client.gametest.light;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The runtime light cases of {@code docs/DESIGN-M3.md} §19 that need the real client, run in the
 * headless container against the light fixture of the server test sources ({@code LightShardFixture}),
 * or, in mode {@code real}, against {@code bin/server-shard} (§26 M3-D step 4). Without
 * {@code SYNAXIS_LIGHT_SERVER} it does nothing; the regression R1 to R8 runs after it either way.
 * <p>
 * The light of the client is what {@link LayerLightEventListener#getLightValue} answers, the light of
 * the server what the fixture computes over its blocks right now ({@code truth}). Every case writes
 * one line {@code L<n> PASS|FAIL <evidence>} to {@code synaxis-gametest/light-report.txt}; the samples
 * per tick go to {@code light-samples.csv}. The player places and breaks through
 * {@code MultiPlayerGameMode} called on the client thread, never through synthetic input.
 */
public final class LightClientGameTest implements FabricClientGameTest {
    private static final int CONNECT_TIMEOUT_TICKS = 20 * 120;
    private static final long ANSWER_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(60);
    /** The ring of a client with a render distance of 12 at chunk (16,16) of the light map. */
    private static final int RING_MIN = 3, RING_MAX = 29;
    private static final int SECTIONS = 16;
    private static final int STEADY_TICKS = 200;

    @Override
    public void runTest(ClientGameTestContext context) {
        final String server = System.getenv("SYNAXIS_LIGHT_SERVER");
        if (server == null || server.isBlank()) return;
        final String mode = Objects.requireNonNullElse(System.getenv("SYNAXIS_LIGHT_MODE"), "default");
        final Run run = new Run(context, mode);
        try {
            context.waitForScreen(TitleScreen.class);
            context.runOnClient(client -> client.options.renderDistance().set(12));
            context.runOnClient(client -> ConnectScreen.startConnecting(new TitleScreen(), client,
                    ServerAddress.parseString(server), new ServerData("light", server, ServerData.Type.OTHER), false, null));
            final boolean inWorld = run.waitUntil(client -> inWorld(client) || client.gui.screen() instanceof DisconnectedScreen,
                    CONNECT_TIMEOUT_TICKS) && context.computeOnClient(LightClientGameTest::inWorld);
            if (!inWorld) {
                run.line("L0", false, "not in the world of " + server);
                return;
            }
            if (mode.equals("real")) run.real();
            else run.fixture(mode.equals("resend"));
        } catch (Throwable t) {
            run.line("L0", false, "aborted: " + t);
            throw t;
        } finally {
            run.write();
            leaveToTitle(context);
        }
    }

    private static boolean inWorld(Minecraft client) {
        return client.level != null && client.player != null;
    }

    private static void leaveToTitle(ClientGameTestContext context) {
        if (context.computeOnClient(client -> client.level != null)) {
            context.runOnClient(client -> client.disconnect(new TitleScreen(), false));
        } else if (!context.computeOnClient(client -> client.gui.screen() instanceof TitleScreen)) {
            context.setScreen(TitleScreen::new);
        }
        context.waitForScreen(TitleScreen.class);
    }

    /** The server's light of a chunk, 16 sections of 2,048 bytes of sky light and of block light. */
    private record Truth(byte[][] sky, byte[][] block) {
        int sky(BlockPos pos) {
            return nibble(sky[pos.getY() >> 4], pos);
        }

        int block(BlockPos pos) {
            return nibble(block[pos.getY() >> 4], pos);
        }

        static int nibble(byte[] section, BlockPos pos) {
            final int index = (pos.getX() & 15) | (pos.getZ() & 15) << 4 | (pos.getY() & 15) << 8;
            return (section[index >> 1] >> ((index & 1) << 2)) & 15;
        }

        boolean sameAs(Truth other) {
            for (int s = 0; s < SECTIONS; s++) {
                if (!Arrays.equals(sky[s], other.sky[s]) || !Arrays.equals(block[s], other.block[s])) {
                    return false;
                }
            }
            return true;
        }
    }

    /** What a nibble by nibble comparison found. */
    private static final class Parity {
        long nibbles;
        long diffs;
        final List<String> details = new ArrayList<>();
        final Map<String, Integer> shapes = new LinkedHashMap<>();

        void add(Parity other) {
            nibbles += other.nibbles;
            diffs += other.diffs;
            for (String detail : other.details) if (details.size() < 40) details.add(detail);
            other.shapes.forEach((shape, count) -> shapes.merge(shape, count, Integer::sum));
        }

        @Override
        public String toString() {
            return "nibbles=" + nibbles + " diffs=" + diffs + " layers=" + shapes
                    + (details.isEmpty() ? "" : " first " + details);
        }
    }

    private static final class Run {
        private final ClientGameTestContext context;
        private final String mode;
        private final Map<String, String> lines = new LinkedHashMap<>();
        private final List<String> samples = new ArrayList<>();
        private final Map<String, BlockPos> sites = new HashMap<>();
        /** The fixture's answer to {@code stats} at the end of the fixture cases, for §15 ④ and the install per tick. */
        private String stats = "";

        Run(ClientGameTestContext context, String mode) {
            this.context = context;
            this.mode = mode;
        }

        void line(String id, boolean pass, String evidence) {
            lines.put(id, id + (pass ? " PASS " : " FAIL ") + evidence);
        }

        void write() {
            final Path dir = FabricLoader.getInstance().getGameDir().resolve("synaxis-gametest");
            try {
                Files.createDirectories(dir);
                final List<String> report = new ArrayList<>();
                report.add("# mode " + mode);
                report.addAll(lines.values());
                if (!stats.isEmpty()) report.add("# stats " + stats);
                Files.write(dir.resolve("light-report.txt"), report, StandardCharsets.UTF_8);
                final List<String> csv = new ArrayList<>();
                csv.add("case,tick,point,x,y,z,sky,block");
                csv.addAll(samples);
                Files.write(dir.resolve("light-samples.csv"), csv, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("could not write the light report", e);
            }
        }

        boolean waitUntil(Predicate<Minecraft> condition, int ticks) {
            try {
                context.waitFor(condition, ticks);
                return true;
            } catch (AssertionError _) {
                return false;
            }
        }

        // ---- The test channel

        byte[] ask(String request) {
            LightProbe.ANSWERS.clear();
            context.runOnClient(_ -> ClientPlayNetworking.send(new LightProbe.Payload(request.getBytes(StandardCharsets.UTF_8))));
            final long until = System.currentTimeMillis() + ANSWER_TIMEOUT_MILLIS;
            while (System.currentTimeMillis() < until) {
                final byte[] answer = LightProbe.ANSWERS.poll();
                if (answer != null) return answer;
                context.waitTick();
            }
            throw new IllegalStateException("no answer to " + request);
        }

        String askText(String request) {
            return new String(ask(request), StandardCharsets.UTF_8);
        }

        long age() {
            return Long.parseLong(askText("age").trim());
        }

        Truth truth(int chunkX, int chunkZ) {
            final ByteBuffer bytes = ByteBuffer.wrap(ask("truth " + chunkX + " " + chunkZ));
            final byte[][] sky = new byte[SECTIONS][2048], block = new byte[SECTIONS][2048];
            for (byte[] section : sky) bytes.get(section);
            for (byte[] section : block) bytes.get(section);
            return new Truth(sky, block);
        }

        /** Light packets the fixture sent this player per chunk, "x,z" to count. */
        Map<String, Integer> sent() {
            final Map<String, Integer> sent = new HashMap<>();
            for (String line : askText("sent").split("\n")) {
                final String[] parts = line.trim().split(" ");
                if (parts.length == 3) sent.put(parts[0] + "," + parts[1], Integer.parseInt(parts[2]));
            }
            return sent;
        }

        /** Until the fixture has no recomputation pending, then {@code ticks} server ticks more, and as many client ticks. */
        void settle(int ticks) {
            while (!askText("idle").trim().equals("pending=0")) context.waitTicks(5);
            final long from = age();
            context.waitTicks(ticks);
            while (age() < from + ticks) context.waitTicks(5);
        }

        // ---- The fixture cases

        void fixture(boolean resend) {
            for (String line : askText("sites").split("\n")) {
                final String[] parts = line.trim().split(" ");
                if (parts.length == 4) {
                    sites.put(parts[0], new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                            Integer.parseInt(parts[3])));
                }
            }
            final boolean ring = awaitRing();
            settle(100);
            line("L1", ring && untouched(), lastEvidence);

            final Parity all = new Parity();
            final List<String> changes = new ArrayList<>();
            int id = 2;
            for (String site : List.of("center", "edge", "corner", "cave")) {
                selfComputes("L" + id++, site, false, resend, all, changes);
            }
            for (String site : List.of("own-center", "own-edge")) {
                selfComputes("L" + id++, site, true, resend, all, changes);
            }
            ringEdge("L8", all, changes);
            seam("L9", all, changes);
            fresh("L10", all, changes);
            fixedPoints(all, changes);
            line("L11", all.diffs == 0 && changes.size() == 16, changes.size() + " changes " + changes + " " + all);
            stats = askText("stats").trim();
        }

        private String lastEvidence = "";

        boolean awaitRing() {
            return waitUntil(client -> {
                for (int x = RING_MIN; x <= RING_MAX; x++) {
                    for (int z = RING_MIN; z <= RING_MAX; z++) if (!client.level.getChunkSource().hasChunk(x, z)) return false;
                }
                return true;
            }, 20 * 180);
        }

        /** L1: the chunks around the spawn and their ring, as sent, against the server's light. */
        boolean untouched() {
            final Parity parity = new Parity();
            for (int x = 13; x <= 20; x++) {
                for (int z = 13; z <= 20; z++) parity.add(compare(x, z, truth(x, z)));
            }
            lastEvidence = "64 chunks 1024 sections " + parity;
            return parity.diffs == 0 && parity.nibbles == 64L * SECTIONS * 4096 * 2;
        }

        /** Client light of one chunk against the server's, nibble by nibble. */
        Parity compare(int chunkX, int chunkZ, Truth truth) {
            return context.computeOnClient(client -> {
                final Parity parity = new Parity();
                final LayerLightEventListener skyLayer = client.level.getLightEngine().getLayerListener(LightLayer.SKY);
                final LayerLightEventListener blockLayer = client.level.getLightEngine().getLayerListener(LightLayer.BLOCK);
                final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                for (int s = 0; s < SECTIONS; s++) {
                    parity.shapes.merge("sky:" + shape(skyLayer.getDataLayerData(SectionPos.of(chunkX, s, chunkZ))), 1, Integer::sum);
                    parity.shapes.merge("block:" + shape(blockLayer.getDataLayerData(SectionPos.of(chunkX, s, chunkZ))), 1, Integer::sum);
                    for (int i = 0; i < 4096; i++) {
                        pos.set(chunkX * 16 + (i & 15), s * 16 + (i >> 8), chunkZ * 16 + ((i >> 4) & 15));
                        final int clientSky = skyLayer.getLightValue(pos), serverSky = truth.sky(pos);
                        final int clientBlock = blockLayer.getLightValue(pos), serverBlock = truth.block(pos);
                        parity.nibbles += 2;
                        if (clientSky != serverSky) {
                            parity.diffs++;
                            if (parity.details.size() < 20) parity.details.add("chunk " + chunkX + "," + chunkZ + " section " + s
                                    + " " + pos.toShortString() + " sky client " + clientSky + " server " + serverSky);
                        }
                        if (clientBlock != serverBlock) {
                            parity.diffs++;
                            if (parity.details.size() < 20) parity.details.add("chunk " + chunkX + "," + chunkZ + " section " + s
                                    + " " + pos.toShortString() + " block client " + clientBlock + " server " + serverBlock);
                        }
                    }
                }
                return parity;
            });
        }

        private static String shape(@Nullable DataLayer layer) {
            if (layer == null) return "null";
            if (layer.isEmpty()) return "zero";
            final byte[] data = layer.getData();
            for (byte b : data) if (b != 0) return "data";
            return "zero";
        }

        /** The chunks of the 3×3 around a block the client holds. */
        List<int[]> heldAround(BlockPos pos) {
            final List<int[]> held = new ArrayList<>();
            final int chunkX = pos.getX() >> 4, chunkZ = pos.getZ() >> 4;
            for (int x = chunkX - 1; x <= chunkX + 1; x++) {
                for (int z = chunkZ - 1; z <= chunkZ + 1; z++) {
                    final int cx = x, cz = z;
                    if (context.computeOnClient(client -> client.level.getChunkSource().hasChunk(cx, cz))) held.add(new int[]{x, z});
                }
            }
            return held;
        }

        /** One change for L11: the client-held 3×3 around it, nibble by nibble. */
        Parity parityAround(String name, BlockPos pos, Parity all, List<String> changes) {
            final Parity parity = new Parity();
            for (int[] chunk : heldAround(pos)) parity.add(compare(chunk[0], chunk[1], truth(chunk[0], chunk[1])));
            all.add(parity);
            changes.add(name + ":" + parity.diffs + "/" + parity.nibbles);
            return parity;
        }

        /** The torch site and the six directions at 1, 4 and 8 blocks. */
        static List<BlockPos> nineteen(BlockPos site) {
            final List<BlockPos> points = new ArrayList<>(19);
            points.add(site);
            for (Direction direction : Direction.values()) {
                for (int distance : new int[]{1, 4, 8}) points.add(site.relative(direction, distance));
            }
            return points;
        }

        int[][] sample(String name, int tick, List<BlockPos> points) {
            final int[][] values = context.computeOnClient(client -> {
                final int[][] read = new int[points.size()][2];
                for (int i = 0; i < points.size(); i++) {
                    read[i][0] = client.level.getBrightness(LightLayer.SKY, points.get(i));
                    read[i][1] = client.level.getBrightness(LightLayer.BLOCK, points.get(i));
                }
                return read;
            });
            for (int i = 0; i < points.size(); i++) {
                final BlockPos p = points.get(i);
                samples.add(name + "," + tick + "," + i + "," + p.getX() + "," + p.getY() + "," + p.getZ() + ","
                        + values[i][0] + "," + values[i][1]);
            }
            return values;
        }

        int[][] expected(List<BlockPos> points) {
            final Map<String, Truth> chunks = new HashMap<>();
            final int[][] expected = new int[points.size()][2];
            for (int i = 0; i < points.size(); i++) {
                final BlockPos p = points.get(i);
                final Truth truth = chunks.computeIfAbsent((p.getX() >> 4) + "," + (p.getZ() >> 4),
                        _ -> truth(p.getX() >> 4, p.getZ() >> 4));
                expected[i][0] = truth.sky(p);
                expected[i][1] = truth.block(p);
            }
            return expected;
        }

        boolean holdsTorch(BlockPos pos) {
            return context.computeOnClient(client -> client.level.getBlockState(pos).is(Blocks.TORCH));
        }

        Map<String, Truth> truthsAround(BlockPos pos) {
            final Map<String, Truth> truths = new HashMap<>();
            final int chunkX = pos.getX() >> 4, chunkZ = pos.getZ() >> 4;
            for (int x = chunkX - 1; x <= chunkX + 1; x++) {
                for (int z = chunkZ - 1; z <= chunkZ + 1; z++) truths.put(x + "," + z, truth(x, z));
            }
            return truths;
        }

        Map<String, Integer> clientPackets() {
            final Map<String, Integer> counts = new HashMap<>();
            LightProbe.LIGHT_PACKETS.forEach((key, list) -> counts.put(ChunkPos.getX(key) + ","
                    + ChunkPos.getZ(key), list.size()));
            return counts;
        }

        static Map<String, Integer> delta(Map<String, Integer> before, Map<String, Integer> after) {
            final Map<String, Integer> delta = new TreeMap<>();
            after.forEach((key, count) -> {
                final int d = count - before.getOrDefault(key, 0);
                if (d != 0) delta.put(key, d);
            });
            return delta;
        }

        /**
         * L2 to L7: a torch the fixture or the player itself places where the player holds the 5×5 for
         * 100 ticks: the final light at t0 or t0 + 1, the same for 200 ticks, the server's, and no light
         * packet (one of the changed sections per target whose light changed with the fallback on).
         */
        void selfComputes(String id, String site, boolean byClient, boolean resend, Parity all, List<String> changes) {
            final BlockPos pos = Objects.requireNonNull(sites.get(site), site);
            final List<BlockPos> points = nineteen(pos);
            final Map<String, Truth> before = truthsAround(pos);
            final Map<String, Integer> clientBefore = clientPackets(), sentBefore = sent();
            if (byClient) {
                context.runOnClient(client -> {
                    client.player.getInventory().setSelectedSlot(0);
                    final BlockPos below = pos.below();
                    client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.atCenterOf(below).add(0, 0.5, 0), Direction.UP, below, false));
                });
            } else {
                askText("place " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " minecraft:torch");
            }
            final List<int[][]> seen = new ArrayList<>();
            int t0 = -1;
            for (int tick = 0; tick < 400 && (t0 < 0 || tick <= t0 + STEADY_TICKS); tick++) {
                seen.add(sample(site, tick, points));
                if (t0 < 0 && holdsTorch(pos)) t0 = tick;
                context.waitTick();
            }
            settle(100);
            final int[][] expected = expected(points);
            final Map<String, Integer> clientDelta = delta(clientBefore, clientPackets());
            final Map<String, Integer> sentDelta = delta(sentBefore, sent());
            boolean pass = t0 >= 0;
            String why = "t0=" + t0;
            if (pass) {
                final int[][] last = seen.getLast();
                if (!Arrays.deepEquals(last, expected)) {
                    pass = false;
                    why += " final is not the server's: " + describe(points, last, expected);
                }
                final boolean atOnce = Arrays.deepEquals(seen.get(t0), last)
                        || (t0 + 1 < seen.size() && Arrays.deepEquals(seen.get(t0 + 1), last));
                if (!atOnce) {
                    pass = false;
                    why += " final not at t0 or t0+1";
                }
                for (int t = t0 + 1; t < seen.size(); t++) {
                    if (!Arrays.deepEquals(seen.get(t), last)) {
                        pass = false;
                        why += " changed at t0+" + (t - t0);
                        break;
                    }
                }
                if (seen.size() < t0 + STEADY_TICKS + 1) {
                    pass = false;
                    why += " only " + (seen.size() - t0) + " samples after t0";
                }
            }
            final Map<String, Truth> after = truthsAround(pos);
            if (resend) {
                // The fallback: one packet of the changed sections for each target whose light changed
                for (Map.Entry<String, Truth> target : after.entrySet()) {
                    final boolean changed = !target.getValue().sameAs(before.get(target.getKey()));
                    final int got = clientDelta.getOrDefault(target.getKey(), 0);
                    if (got != (changed ? 1 : 0)) {
                        pass = false;
                        why += " " + target.getKey() + " got " + got + " light packets, changed=" + changed;
                    }
                    // The packets of this change are the last the chunk got; none of them may be a full one
                    final String[] xz = target.getKey().split(",");
                    final List<LightProbe.LightPacket> packets = LightProbe.lightPackets(Integer.parseInt(xz[0]),
                            Integer.parseInt(xz[1]));
                    if (packets.subList(Math.max(0, packets.size() - got), packets.size()).stream()
                            .anyMatch(LightProbe.LightPacket::full)) {
                        pass = false;
                        why += " " + target.getKey() + " got a full light packet";
                    }
                }
                for (Map.Entry<String, Integer> chunk : clientDelta.entrySet()) {
                    if (!after.containsKey(chunk.getKey())) {
                        pass = false;
                        why += " a light packet of " + chunk.getKey() + " outside the 3x3";
                    }
                }
                if (!clientDelta.equals(sentDelta)) {
                    pass = false;
                    why += " light packets: client " + clientDelta + " fixture " + sentDelta;
                }
            } else if (!clientDelta.isEmpty() || !sentDelta.isEmpty()) {
                pass = false;
                why += " light packets: client " + clientDelta + " fixture " + sentDelta;
            }
            final Parity parity = parityAround(site, pos, all, changes);
            line(id, pass, site + " " + pos.toShortString() + " " + why + " client_packets=" + clientDelta
                    + " fixture_sent=" + sentDelta + " 3x3 " + parity);
        }

        static String describe(List<BlockPos> points, int[][] got, int[][] expected) {
            final StringBuilder out = new StringBuilder();
            for (int i = 0; i < points.size(); i++) {
                if (got[i][0] != expected[i][0] || got[i][1] != expected[i][1]) {
                    out.append(points.get(i).toShortString()).append(" client ").append(got[i][0]).append('/')
                            .append(got[i][1]).append(" server ").append(expected[i][0]).append('/').append(expected[i][1]).append("; ");
                }
            }
            return out.toString();
        }

        /**
         * L8: a torch just past the ring, in (30,16): no block update, one full light packet for each
         * of (29,15), (29,16) and (29,17) whose light changed, the 15 points in (29,16) at the old light
         * until the packet, the server's within 2 ticks of it, then 200 ticks the same.
         */
        void ringEdge(String id, Parity all, List<String> changes) {
            final BlockPos pos = Objects.requireNonNull(sites.get("ring-edge"));
            final List<BlockPos> points = new ArrayList<>();
            for (int distance : new int[]{1, 4, 8}) {
                final BlockPos base = pos.west(distance);
                points.add(base);
                points.add(base.above());
                points.add(base.below());
                points.add(base.north());
                points.add(base.south());
            }
            final Map<String, Truth> before = Map.of("29,15", truth(29, 15), "29,16", truth(29, 16), "29,17", truth(29, 17));
            final Map<String, Integer> clientBefore = clientPackets();
            final int updatesBefore = LightProbe.blockUpdates(pos);
            final int[][] old = sample("ring-edge", -1, points);
            askText("place " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " minecraft:torch");
            final List<int[][]> seen = new ArrayList<>();
            int arrived = -1;
            for (int tick = 0; tick < 1200 && (arrived < 0 || tick <= arrived + STEADY_TICKS); tick++) {
                seen.add(sample("ring-edge", tick, points));
                if (arrived < 0 && !LightProbe.lightPackets(29, 16).isEmpty()
                        && LightProbe.lightPackets(29, 16).size() > clientBefore.getOrDefault("29,16", 0)) arrived = tick;
                context.waitTick();
            }
            settle(100);
            final int[][] expected = expected(points);
            final Map<String, Integer> clientDelta = delta(clientBefore, clientPackets());
            boolean pass = arrived >= 0;
            String why = "packet at tick " + arrived;
            int changesSeen = 0;
            for (int t = 1; t < seen.size(); t++) if (!Arrays.deepEquals(seen.get(t), seen.get(t - 1))) changesSeen++;
            if (changesSeen > 1) {
                pass = false;
                why += " the light changed " + changesSeen + " times";
            }
            if (arrived >= 0) {
                for (int t = 0; t < arrived; t++) {
                    if (!Arrays.deepEquals(seen.get(t), old)) {
                        pass = false;
                        why += " not the old light before the packet at tick " + t;
                        break;
                    }
                }
                int right = -1;
                for (int t = arrived; t < seen.size(); t++) {
                    if (Arrays.deepEquals(seen.get(t), expected)) {
                        right = t;
                        break;
                    }
                }
                if (right < 0 || right > arrived + 2) {
                    pass = false;
                    why += " the server's light at tick " + right + ": " + describe(points, seen.getLast(), expected);
                } else {
                    for (int t = right; t < seen.size(); t++) {
                        if (!Arrays.deepEquals(seen.get(t), expected)) {
                            pass = false;
                            why += " changed again at tick " + t;
                            break;
                        }
                    }
                }
            }
            for (String chunk : List.of("29,15", "29,16", "29,17")) {
                final String[] xz = chunk.split(",");
                final boolean changed = !truth(Integer.parseInt(xz[0]), Integer.parseInt(xz[1])).sameAs(before.get(chunk));
                if (clientDelta.getOrDefault(chunk, 0) != (changed ? 1 : 0)) {
                    pass = false;
                    why += " " + chunk + " changed=" + changed + " packets " + clientDelta.getOrDefault(chunk, 0);
                }
            }
            if (clientDelta.keySet().stream().anyMatch(chunk -> !before.containsKey(chunk))) {
                pass = false;
                why += " packets for other chunks " + clientDelta;
            }
            final boolean allFull = LightProbe.lightPackets(29, 16).stream().allMatch(LightProbe.LightPacket::full);
            if (!allFull) {
                pass = false;
                why += " not a full light packet";
            }
            if (LightProbe.blockUpdates(pos) != updatesBefore) {
                pass = false;
                why += " the client got the block update";
            }
            final Parity parity = parityAround("ring-edge", pos, all, changes);
            line(id, pass, pos.toShortString() + " " + why + " client_packets=" + clientDelta + " 3x3 " + parity);
        }

        /**
         * L9: glowstone in U = (30,12), which the client lacks, then a torch in C = (29,11) next to it,
         * placed and broken: the targets whose 3×3 holds x = 30 get one full packet each; the client's
         * light of (28..29, 10..13) is the server's within 100 ticks of the last packet and 200 ticks
         * later.
         */
        void seam(String id, Parity all, List<String> changes) {
            final BlockPos source = Objects.requireNonNull(sites.get("seam-source")), seam = Objects.requireNonNull(sites.get("seam"));
            final StringBuilder why = new StringBuilder();
            boolean pass = true;
            final String[][] steps = {
                    {"seam-source", "place " + source.getX() + " " + source.getY() + " " + source.getZ() + " minecraft:glowstone"},
                    {"seam-place", "place " + seam.getX() + " " + seam.getY() + " " + seam.getZ() + " minecraft:torch"},
                    {"seam-break", "break " + seam.getX() + " " + seam.getY() + " " + seam.getZ()}};
            for (String[] step : steps) {
                final boolean sourceStep = step[0].equals("seam-source");
                final BlockPos at = sourceStep ? source : seam;
                final List<String> targets = sourceStep ? List.of("29,11", "29,12", "29,13") : List.of("29,10", "29,11", "29,12");
                final Map<String, Truth> before = new HashMap<>();
                for (String target : targets) before.put(target, truthOf(target));
                final Map<String, Integer> clientBefore = clientPackets();
                final int updatesBefore = LightProbe.blockUpdates(at);
                askText(step[1]);
                settle(100);
                final Map<String, Integer> clientDelta = delta(clientBefore, clientPackets());
                for (String target : targets) {
                    final boolean changed = !truthOf(target).sameAs(before.get(target));
                    final int expected = sourceStep ? (changed ? 1 : 0) : 1;
                    if (clientDelta.getOrDefault(target, 0) != expected) {
                        pass = false;
                        why.append(step[0]).append(' ').append(target).append(" packets ").append(clientDelta.getOrDefault(target, 0))
                                .append(" expected ").append(expected).append("; ");
                    }
                }
                if (!sourceStep) {
                    for (String self : List.of("28,10", "28,11", "28,12")) {
                        if (clientDelta.getOrDefault(self, 0) != 0) {
                            pass = false;
                            why.append(step[0]).append(' ').append(self).append(" got a light packet; ");
                        }
                    }
                }
                final int updates = LightProbe.blockUpdates(at) - updatesBefore;
                if (updates != (sourceStep ? 0 : 1)) {
                    pass = false;
                    why.append(step[0]).append(" block updates ").append(updates).append("; ");
                }
                final Parity parity = new Parity();
                for (int x = 28; x <= 29; x++) {
                    for (int z = 10; z <= 13; z++) parity.add(compare(x, z, truth(x, z)));
                }
                final Map<String, Integer> steady = clientPackets();
                context.waitTicks(STEADY_TICKS);
                final Parity later = new Parity();
                for (int x = 28; x <= 29; x++) {
                    for (int z = 10; z <= 13; z++) later.add(compare(x, z, truth(x, z)));
                }
                if (parity.diffs != 0 || later.diffs != 0 || !delta(steady, clientPackets()).isEmpty()) pass = false;
                why.append(step[0]).append(" packets ").append(clientDelta).append(" (28..29,10..13) ").append(parity)
                        .append(" 200 ticks later diffs=").append(later.diffs).append("; ");
                final Parity around = parityAround(step[0], at, all, changes);
                why.append(" 3x3 diffs=").append(around.diffs).append("; ");
            }
            line(id, pass, why.toString());
        }

        Truth truthOf(String chunk) {
            final String[] xz = chunk.split(",");
            return truth(Integer.parseInt(xz[0]), Integer.parseInt(xz[1]));
        }

        /**
         * L10: the player leaves for (28,27) until it no longer holds (3..7, 3..7), then comes to
         * (5,5); 30 ticks later a torch at its center: one block update, one full light packet for each
         * of (4..6, 4..6), and their light the server's within 100 ticks of the last packet and 200
         * ticks later. Back at the spawn chunk after it, with the whole ring received and 100 ticks.
         */
        void fresh(String id, Parity all, List<String> changes) {
            final BlockPos far = Objects.requireNonNull(sites.get("far")), fresh = Objects.requireNonNull(sites.get("fresh"));
            askText("tp " + (far.getX() + 0.5) + " " + far.getY() + " " + (far.getZ() + 0.5));
            final boolean left = waitUntil(client -> {
                for (int x = 3; x <= 7; x++) {
                    for (int z = 3; z <= 7; z++) if (client.level.getChunkSource().hasChunk(x, z)) return false;
                }
                return true;
            }, 20 * 180);
            askText("tp " + (fresh.getX() - 3.5) + " " + fresh.getY() + " " + (fresh.getZ() - 3.5));
            final long arrived = age();
            while (age() < arrived + 30) context.waitTicks(2);
            final Map<String, Integer> clientBefore = clientPackets();
            final int updatesBefore = LightProbe.blockUpdates(fresh);
            askText("place " + fresh.getX() + " " + fresh.getY() + " " + fresh.getZ() + " minecraft:torch");
            settle(100);
            final Map<String, Integer> clientDelta = delta(clientBefore, clientPackets());
            boolean pass = left;
            final StringBuilder why = new StringBuilder("left (3..7,3..7)=" + left + "; ");
            for (int x = 4; x <= 6; x++) {
                for (int z = 4; z <= 6; z++) {
                    if (clientDelta.getOrDefault(x + "," + z, 0) != 1) {
                        pass = false;
                        why.append(x).append(',').append(z).append(" packets ").append(clientDelta.getOrDefault(x + "," + z, 0)).append("; ");
                    }
                    if (!LightProbe.lightPackets(x, z).stream().allMatch(LightProbe.LightPacket::full)) {
                        pass = false;
                        why.append(x).append(',').append(z).append(" not full; ");
                    }
                }
            }
            final int updates = LightProbe.blockUpdates(fresh) - updatesBefore;
            if (updates != 1) {
                pass = false;
                why.append("block updates ").append(updates).append("; ");
            }
            final Parity parity = new Parity();
            for (int x = 4; x <= 6; x++) {
                for (int z = 4; z <= 6; z++) parity.add(compare(x, z, truth(x, z)));
            }
            final Map<String, Integer> steady = clientPackets();
            context.waitTicks(STEADY_TICKS);
            final Parity later = new Parity();
            for (int x = 4; x <= 6; x++) {
                for (int z = 4; z <= 6; z++) later.add(compare(x, z, truth(x, z)));
            }
            if (parity.diffs != 0 || later.diffs != 0 || !delta(steady, clientPackets()).isEmpty()) pass = false;
            final Parity around = parityAround("fresh", fresh, all, changes);
            final BlockPos spawn = Objects.requireNonNull(sites.get("spawn"));
            askText("tp " + (spawn.getX() + 0.5) + " " + spawn.getY() + " " + (spawn.getZ() + 0.5));
            final boolean back = awaitRing();
            settle(100);
            line(id, pass && back, fresh.toShortString() + " " + why + "packets " + clientDelta + " (4..6,4..6) " + parity
                    + " 200 ticks later diffs=" + later.diffs + " 3x3 diffs=" + around.diffs + " back=" + back);
        }

        /** The last five changes of L11: the shaft cap, the pillar top twice, the bottom chamber and the flicker. */
        void fixedPoints(Parity all, List<String> changes) {
            final BlockPos cap = Objects.requireNonNull(sites.get("shaft-cap")), top = Objects.requireNonNull(sites.get("top"));
            final BlockPos bottom = Objects.requireNonNull(sites.get("bottom")), flicker = Objects.requireNonNull(sites.get("flicker"));
            change("shaft-cap", cap, "break " + xyz(cap), all, changes);
            change("top-place", top, "place " + xyz(top) + " minecraft:stone", all, changes);
            change("top-break", top, "break " + xyz(top), all, changes);
            change("bottom", bottom, "place " + xyz(bottom) + " minecraft:stone", all, changes);
            // Five changes of one block within 5 ticks: the requests go out together, answered afterwards
            LightProbe.ANSWERS.clear();
            for (int i = 0; i < 5; i++) {
                final String request = i % 2 == 0 ? "place " + xyz(flicker) + " minecraft:torch" : "break " + xyz(flicker);
                context.runOnClient(_ -> ClientPlayNetworking.send(new LightProbe.Payload(request.getBytes(StandardCharsets.UTF_8))));
            }
            final long until = System.currentTimeMillis() + ANSWER_TIMEOUT_MILLIS;
            while (LightProbe.ANSWERS.size() < 5 && System.currentTimeMillis() < until) context.waitTick();
            LightProbe.ANSWERS.clear();
            settle(100);
            parityAround("flicker", flicker, all, changes);
        }

        void change(String name, BlockPos pos, String request, Parity all, List<String> changes) {
            askText(request);
            settle(100);
            parityAround(name, pos, all, changes);
        }

        static String xyz(BlockPos pos) {
            return pos.getX() + " " + pos.getY() + " " + pos.getZ();
        }

        // ---- The real shard

        /**
         * Mode {@code real} against {@code bin/server-shard}: survival, digs the block east of the one
         * under its feet, block by block as a player does, and checks that the sky light of the cell
         * is 15 within a tick of the block going and stays so for 200 ticks, with no light packet. Then
         * it writes the ready marker the host waits for before its SIGTERM, and stays until the
         * shard's stop disconnects it, 120 s at most.
         */
        void real() {
            // The 5×5 around the spawn delivered 100 server ticks ago: the client waits in real time,
            // as it has no channel to the shard's clock
            waitUntil(client -> {
                final int chunkX = client.player.blockPosition().getX() >> 4, chunkZ = client.player.blockPosition().getZ() >> 4;
                for (int x = chunkX - 3; x <= chunkX + 3; x++) {
                    for (int z = chunkZ - 3; z <= chunkZ + 3; z++) if (!client.level.getChunkSource().hasChunk(x, z)) return false;
                }
                return true;
            }, 20 * 180);
            sleep(TimeUnit.SECONDS.toMillis(8));
            context.waitTicks(20);
            final BlockPos target = context.computeOnClient(client -> client.player.blockPosition().below().east());
            final String block = context.computeOnClient(client -> client.level.getBlockState(target).toString());
            final String gameMode = context.computeOnClient(client -> client.gameMode.getPlayerMode().getName());
            final int packetsBefore = LightProbe.lightPacketsInAll();
            context.runOnClient(client -> client.gameMode.startDestroyBlock(target, Direction.UP));
            final List<BlockPos> points = List.of(target);
            final List<int[][]> seen = new ArrayList<>();
            int t0 = -1;
            for (int tick = 0; tick < 1200 && (t0 < 0 || tick <= t0 + STEADY_TICKS); tick++) {
                if (t0 < 0) context.runOnClient(client -> client.gameMode.continueDestroyBlock(target, Direction.UP));
                context.waitTick();
                seen.add(sample("real", tick, points));
                if (t0 < 0 && context.computeOnClient(client -> client.level.getBlockState(target).isAir())) t0 = tick;
            }
            boolean pass = t0 >= 0;
            String why = gameMode + " dug " + block + " at " + target.toShortString() + " t0=" + t0;
            if (pass) {
                final int at = t0 + 1 < seen.size() && seen.get(t0)[0][0] != 15 ? t0 + 1 : t0;
                if (seen.get(at)[0][0] != 15) {
                    pass = false;
                    why += " sky " + seen.get(at)[0][0] + " at t0+" + (at - t0);
                }
                for (int t = at; t < seen.size(); t++) {
                    if (seen.get(t)[0][0] != 15) {
                        pass = false;
                        why += " sky " + seen.get(t)[0][0] + " at t0+" + (t - t0);
                        break;
                    }
                }
                if (seen.size() < t0 + STEADY_TICKS + 1) {
                    pass = false;
                    why += " only " + (seen.size() - t0) + " samples after t0";
                }
            }
            final int packets = LightProbe.lightPacketsInAll() - packetsBefore;
            if (packets != 0) {
                pass = false;
                why += " light packets " + packets;
            }
            line("L1", pass, why + " light_packets=" + packets);
            write();
            final String marker = System.getenv("SYNAXIS_LIGHT_MARKER");
            if (marker != null && !marker.isBlank()) {
                try {
                    Files.write(Path.of(marker), new byte[0]);
                } catch (IOException e) {
                    line("L2", false, "could not write the marker " + marker + ": " + e);
                    return;
                }
            }
            final long start = System.currentTimeMillis();
            boolean disconnected = false;
            while (System.currentTimeMillis() - start < TimeUnit.SECONDS.toMillis(120)) {
                context.waitTicks(10);
                disconnected = context.computeOnClient(client -> client.level == null || client.gui.screen() instanceof DisconnectedScreen);
                if (disconnected) break;
            }
            line("L2", disconnected, "marker " + marker + " written, disconnected by the shard's stop after "
                    + (System.currentTimeMillis() - start) + " ms");
        }

        private static void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}
