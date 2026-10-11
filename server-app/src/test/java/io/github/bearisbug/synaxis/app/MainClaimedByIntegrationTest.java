package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.app.shard.ShardArrivals;
import io.github.bearisbug.synaxis.app.shard.ShardDirectory;
import io.github.bearisbug.synaxis.app.store.PlayerStateSection;
import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.app.store.PositionRule;
import io.github.bearisbug.synaxis.app.transfer.TransferTicket;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.inventory.PlayerInventory;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code Main#claimedBy} on a map shard in the directory mode, the one process where both of its parameters are there
 * ({@code docs/DESIGN-PROD.md} {@code API-PROD-032} 凭票据到达, v0.9.73): the arrivals put the respawn point on the
 * arrival of the gate back to the source region, and the player section takes the player for one that stays there.
 * A transferred player whose position was saved on this release inside the bounds spawns at the gate all the same,
 * with the inventory, the held slot and the health of its saved state.
 */
@EnvTest
class MainClaimedByIntegrationTest {

    private static final SynxManifest.Bounds BOUNDS = new SynxManifest.Bounds(0, 0, 3, 3);
    /** Where {@code Main#serveMap} would put a player that comes without a ticket. */
    private static final Pos SPAWN = new Pos(8.5, 40, 8.5);
    /** The arrival of the gate of r0 back to r1. */
    private static final Pos ARRIVAL = new Pos(24.5, 40, 8.5, 90, 0);
    /** Inside the bounds, on the release of the rule. */
    private static final Pos SAVED = new Pos(40.5, 40, 40.5, 180, 0);

    @Test
    void aTransferredPlayerSpawnsAtTheGateWithTheInventoryHeldSlotAndHealthItLeftWith(Env env) {
        final ShardDirectory directory = new ShardDirectory("demo", List.of(
                new ShardDirectory.Region("r0", BOUNDS, List.of(new ShardDirectory.Gate("r0-east",
                        new ShardDirectory.Frame(60, 40, 0, 63, 43, 15), "r1", ARRIVAL))),
                new ShardDirectory.Region("r1", new SynxManifest.Bounds(4, 0, 7, 3), List.of())),
                List.of(new ShardDirectory.Channel("r0-c1", "r0", 1, "127.0.0.1", 25601, 10),
                        new ShardDirectory.Channel("r1-c1", "r1", 1, "127.0.0.1", 25602, 10)), "r0-c1");
        final ShardArrivals arrivals = new ShardArrivals(directory, (_, _) -> {
        }, System::currentTimeMillis);
        // Steps (4) and (5) of Main for a map shard: the store and the player section on the host node, the map rule
        final PlayerStore store = PlayerStore.inMemory(env.process(), (_, _) -> {
        });
        final EventNode<Event> host = EventNode.all("synaxis-host");
        env.process().eventHandler().addChild(host);
        store.attach(host);
        final PlayerStateSection playerState = new PlayerStateSection(env.process().registries());
        playerState.install(store, host);
        playerState.useRule(new PositionRule.MapShard("demo", "b1", BOUNDS));
        final BiConsumer<Player, TransferTicket> claimedBy = Main.claimedBy(arrivals, playerState);
        // AdmissionGate on the configuration thread, after the respawn point is set: the load, a ticket's claim, the
        // admission
        final AtomicReference<TransferTicket> ticket = new AtomicReference<>();
        host.addListener(AsyncPlayerConfigurationEvent.class, event -> {
            final Player player = event.getPlayer();
            if (!store.load(player)) throw new IllegalStateException("not loaded: " + player.getUsername());
            final TransferTicket claimed = ticket.get();
            if (claimed != null) claimedBy.accept(player, claimed);
            if (!store.admitted(player)) throw new IllegalStateException("not admitted: " + player.getUsername());
        });
        final Instance instance = env.createFlatInstance();
        final UUID account = UUID.randomUUID();

        final Player first = join(env, account, instance);
        first.acquirable().sync(_ -> {
            first.teleport(SAVED).join();
            first.setHeldItemSlot((byte) 4);
            first.setHealth(9);
            first.getInventory().clear();
            first.getInventory().setItemStack(4, ItemStack.of(Material.DIRT));
        });
        first.getPlayerConnection().disconnect();
        assertTrue(env.tickWhile(() -> !released(store, playerState, first), Duration.ofSeconds(10)), "the logout save");

        ticket.set(new TransferTicket(1, account, 1, "r1-c1", "r0-c1", 1, new byte[16], System.currentTimeMillis(),
                System.currentTimeMillis() + TransferTicket.LIFETIME_MS));
        final Player arrived = join(env, account, instance);
        for (int tick = 0; tick < 10; tick++) env.tick();

        assertEquals(ARRIVAL, arrived.getRespawnPoint(), "the arrivals moved the respawn point onto the gate");
        assertEquals(ARRIVAL, arrived.getPosition(), "spawned at the gate, not taken to the saved position");
        assertFalse(playerState.isFresh(arrived), "the saved state was loaded");
        assertEquals(4, arrived.getHeldSlot());
        assertEquals(9f, arrived.getHealth());
        for (int slot = 0; slot < PlayerInventory.INVENTORY_SIZE; slot++) {
            assertEquals(slot == 4 ? ItemStack.of(Material.DIRT) : ItemStack.AIR, arrived.getInventory().getItemStack(slot),
                    "slot " + slot);
        }
    }

    /** A connection whose disconnect is the real one. */
    private static final class Connection extends PlayerConnection {
        Connection(ServerProcess process) {
            super(process);
            setServerState(ConnectionState.CONFIGURATION);
        }

        @Override
        public void sendPacket(SendablePacket packet) {
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("localhost", 25565);
        }
    }

    /**
     * Connects a player and takes it through the configuration into the instance, as a client acknowledging every step
     * does; its respawn point is where it spawns, set before the host node's listeners run, as {@code Main#serveMap}
     * sets it on the root node.
     */
    private static Player join(Env env, UUID account, Instance instance) {
        final ServerProcess process = env.process();
        final Connection connection = new Connection(process);
        final Player player = process.connection().createPlayer(connection, new GameProfile(account, "Traveller"));
        assertNotNull(player, "the uuid is free");
        player.eventNode().addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(instance);
            event.getPlayer().setRespawnPoint(SPAWN);
        });
        CompletableFuture.runAsync(() -> {
            process.connection().doConfiguration(player, false);
            process.connection().transitionConfigToPlay(player);
        }, Thread.ofVirtual()::start).join();
        connection.setClientState(ConnectionState.PLAY);
        connection.setServerState(ConnectionState.PLAY);
        process.connection().updateWaitingPlayers();
        return player;
    }

    /** Whether the store let the player go, its logout save written: it holds no value of it any more. */
    private static boolean released(PlayerStore store, PlayerStateSection playerState, Player player) {
        try {
            store.hostValue(player, playerState);
            return false;
        } catch (IllegalStateException _) {
            return true;
        }
    }
}
