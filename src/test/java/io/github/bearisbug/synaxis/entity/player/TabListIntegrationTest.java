package io.github.bearisbug.synaxis.entity.player;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.packet.server.play.PlayerInfoUpdatePacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tab list without a packet per pair of players ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-027},
 * {@code API-PROD-020}, §19 「进服只收一个多条目 PlayerInfo 包」).
 */
@EnvTest
public class TabListIntegrationTest {

    @Test
    public void aJoiningPlayerGetsEveryoneOnlineInOnePacket(Env env) {
        final Instance instance = env.createFlatInstance();
        final List<TestConnection> connections = new ArrayList<>();
        final Set<UUID> online = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            final TestConnection connection = env.createConnection(new GameProfile(UUID.randomUUID(), "online" + i));
            online.add(connection.connect(instance, new Pos(0, 40, 0)).getUuid());
            connections.add(connection);
        }
        final List<Collector<PlayerInfoUpdatePacket>> theirs = new ArrayList<>();
        for (TestConnection connection : connections) theirs.add(connection.trackIncoming(PlayerInfoUpdatePacket.class));

        final TestConnection joining = env.createConnection(new GameProfile(UUID.randomUUID(), "joining"));
        final Collector<PlayerInfoUpdatePacket> received = joining.trackIncoming(PlayerInfoUpdatePacket.class);
        final UUID joiner = joining.connect(instance, new Pos(0, 40, 0)).getUuid();

        int carryingOthers = 0;
        final List<PlayerInfoUpdatePacket> adds = adds(received.collect());
        for (PlayerInfoUpdatePacket packet : adds) {
            final Set<UUID> uuids = uuids(packet);
            if (uuids.contains(joiner)) {
                assertEquals(Set.of(joiner), uuids, "its own entry");
            } else {
                assertEquals(online, uuids, "every player online, in the same packet");
                carryingOthers++;
            }
        }
        assertEquals(1, carryingOthers, "one packet for the players already online, not one each: " + adds);
        // Each player already online gets the newcomer once
        for (Collector<PlayerInfoUpdatePacket> collector : theirs) {
            final List<PlayerInfoUpdatePacket> packets = adds(collector.collect());
            assertEquals(1, packets.size());
            assertEquals(Set.of(joiner), uuids(packets.getFirst()));
        }
    }

    @Test
    public void latenciesOnlyGoOutAllTogether(Env env) {
        final Instance instance = env.createFlatInstance();
        final List<TestConnection> connections = new ArrayList<>();
        final List<Player> players = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final TestConnection connection = env.createConnection(new GameProfile(UUID.randomUUID(), "pinging" + i));
            players.add(connection.connect(instance, new Pos(0, 40, 0)));
            connections.add(connection);
        }
        final List<Collector<PlayerInfoUpdatePacket>> before = new ArrayList<>();
        for (TestConnection connection : connections) before.add(connection.trackIncoming(PlayerInfoUpdatePacket.class));
        // Keep alive answers store the latency and send nothing, nor does a tick before the interval
        for (int round = 0; round < 10; round++) {
            for (int i = 0; i < players.size(); i++) players.get(i).refreshLatency(100 * i + round);
            env.tick();
        }
        for (Collector<PlayerInfoUpdatePacket> collector : before)
            assertEquals(List.of(), latencies(collector.collect()), "no latency before the batch");

        final List<Collector<PlayerInfoUpdatePacket>> batch = new ArrayList<>();
        for (TestConnection connection : connections) batch.add(connection.trackIncoming(PlayerInfoUpdatePacket.class));
        env.process().connection().sendTabListLatencies();
        for (Collector<PlayerInfoUpdatePacket> collector : batch) {
            final List<PlayerInfoUpdatePacket> latencies = latencies(collector.collect());
            assertEquals(1, latencies.size(), "one packet per player");
            final Map<UUID, Integer> byPlayer = new HashMap<>();
            for (PlayerInfoUpdatePacket.Entry entry : latencies.getFirst().entries())
                byPlayer.put(entry.uuid(), entry.latency());
            assertEquals(3, byPlayer.size());
            for (int i = 0; i < players.size(); i++) assertEquals(100 * i + 9, byPlayer.get(players.get(i).getUuid()));
        }
    }

    @Test
    public void theVisibilityUnlistsWithoutHidingTheEntry(Env env) {
        final Instance instance = env.createFlatInstance();
        env.process().connection().setTabListVisibility((_, subject) -> !subject.getUsername().equals("hidden"));
        final TestConnection watching = env.createConnection(new GameProfile(UUID.randomUUID(), "watching"));
        final Player watcher = watching.connect(instance, new Pos(0, 40, 0));
        final Collector<PlayerInfoUpdatePacket> watcherSees = watching.trackIncoming(PlayerInfoUpdatePacket.class);

        final TestConnection hiding = env.createConnection(new GameProfile(UUID.randomUUID(), "hidden"));
        final Collector<PlayerInfoUpdatePacket> hiddenSees = hiding.trackIncoming(PlayerInfoUpdatePacket.class);
        final Player hidden = hiding.connect(instance, new Pos(0, 40, 0));

        // The watcher gets the entry, for the skin, but not listed; the hidden player lists itself
        assertEquals(false, listed(watcherSees.collect(), hidden.getUuid()));
        final List<PlayerInfoUpdatePacket> ownView = hiddenSees.collect();
        assertEquals(true, listed(ownView, hidden.getUuid()));
        assertEquals(true, listed(ownView, watcher.getUuid()));

        // Lifting it lists the hidden player again, one packet per viewer
        final Collector<PlayerInfoUpdatePacket> relisted = watching.trackIncoming(PlayerInfoUpdatePacket.class);
        env.process().connection().setTabListVisibility(null);
        final List<PlayerInfoUpdatePacket> packets = relisted.collect();
        assertEquals(1, packets.size());
        assertTrue(packets.getFirst().actions().contains(PlayerInfoUpdatePacket.Action.UPDATE_LISTED));
        assertEquals(true, listed(packets, hidden.getUuid()));
    }

    private static List<PlayerInfoUpdatePacket> adds(List<PlayerInfoUpdatePacket> packets) {
        return packets.stream().filter(packet -> packet.actions().contains(PlayerInfoUpdatePacket.Action.ADD_PLAYER)).toList();
    }

    private static List<PlayerInfoUpdatePacket> latencies(List<PlayerInfoUpdatePacket> packets) {
        return packets.stream().filter(packet -> packet.actions().contains(PlayerInfoUpdatePacket.Action.UPDATE_LATENCY)
                && !packet.actions().contains(PlayerInfoUpdatePacket.Action.ADD_PLAYER)).toList();
    }

    private static Set<UUID> uuids(PlayerInfoUpdatePacket packet) {
        final Set<UUID> uuids = new HashSet<>();
        for (PlayerInfoUpdatePacket.Entry entry : packet.entries()) uuids.add(entry.uuid());
        return uuids;
    }

    /** The last {@code listed} flag the packets carry for a player, null if none does. */
    private static @Nullable Boolean listed(List<PlayerInfoUpdatePacket> packets, UUID uuid) {
        Boolean listed = null;
        for (PlayerInfoUpdatePacket packet : packets) {
            if (!packet.actions().contains(PlayerInfoUpdatePacket.Action.UPDATE_LISTED)) continue;
            for (PlayerInfoUpdatePacket.Entry entry : packet.entries())
                if (entry.uuid().equals(uuid)) listed = entry.listed();
        }
        return listed;
    }
}
