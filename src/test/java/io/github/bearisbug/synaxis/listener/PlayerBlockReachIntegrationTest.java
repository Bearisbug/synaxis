package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.coordinate.BlockFace;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.GameMode;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.entity.PlayerHand;
import io.github.bearisbug.synaxis.entity.attribute.Attribute;
import io.github.bearisbug.synaxis.event.EventFilter;
import io.github.bearisbug.synaxis.event.player.PlayerBlockBreakEvent;
import io.github.bearisbug.synaxis.event.player.PlayerBlockInteractEvent;
import io.github.bearisbug.synaxis.event.player.PlayerEditSignEvent;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerActionPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerBlockPlacementPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientUpdateSignPacket;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.AcknowledgeBlockChangePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.BlockChangePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.BlockEntityDataPacket;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A dig or a block use whose block is out of the player's reach is refused
 * ({@code docs/DESIGN-PROD.md} {@code API-PROD-030}): the vanilla server's rule, the block's cube
 * at least the player's block interaction range plus one block from its eyes. Security review M3,
 * finding S1-01.
 * <p>
 * The player never ticks here: the test thread hands it its packets.
 */
@EnvTest
public class PlayerBlockReachIntegrationTest {

    private static final Pos SPAWN = new Pos(8.5, 40, 8.5);

    private static void send(Player player, ClientPacket packet) {
        player.addPacketToQueue(packet);
        player.interpretPacketQueue();
    }

    private static void dig(Player player, ClientPlayerActionPacket.Status status, int x, int y, int z, int sequence) {
        send(player, new ClientPlayerActionPacket(status, new Vec(x, y, z), BlockFace.TOP, sequence));
    }

    /**
     * S1-01 as reported: a survival player with nothing in hand sends one FINISHED_DIGGING, no
     * STARTED_DIGGING, for a stone block 13 chunks away on both axes.
     */
    @Test
    public void aFinishedDiggingOutOfReachBreaksNothing(Env env) {
        final Instance instance = env.createFlatInstance();
        final Vec target = new Vec(13 * 16 + 8, 39, 13 * 16 + 8);
        instance.loadChunk(target.chunkX(), target.chunkZ()).join();
        final TestConnection connection = env.createConnection();
        final Player player = connection.connect(instance, SPAWN);
        assertEquals(GameMode.SURVIVAL, player.getGameMode());
        assertEquals(Block.STONE, instance.getBlock(target));
        final Collector<PlayerBlockBreakEvent> breaks = env.trackEvent(PlayerBlockBreakEvent.class, EventFilter.PLAYER, player);
        final Collector<AcknowledgeBlockChangePacket> acks = connection.trackIncoming(AcknowledgeBlockChangePacket.class);

        dig(player, ClientPlayerActionPacket.Status.FINISHED_DIGGING, target.blockX(), target.blockY(), target.blockZ(), 7);

        assertEquals(Block.STONE, instance.getBlock(target), "dug 293.45 blocks from the eyes");
        breaks.assertEmpty();
        acks.assertSingle(ack -> assertEquals(7, ack.sequence()));
    }

    /**
     * A dig out of reach is answered with the acknowledgement alone, as the vanilla server answers it:
     * the data of a block entity is not sent again, to the player or to its viewers, so that a modified
     * client cannot have a chest anywhere in a loaded chunk sent to everyone around it, packet after
     * packet. A dig refused for another reason, adventure mode with nothing in hand here, has it sent
     * again to the digger alone, whose client predicted the break; the viewers predicted nothing.
     */
    @Test
    public void aDigOutOfReachSendsNoBlockEntityData(Env env) {
        final Instance instance = env.createFlatInstance();
        instance.loadChunk(2, 0).join();
        // At eye height: 30.5 blocks east of the eyes, and 1.5
        instance.setBlock(39, 41, 8, Block.CHEST);
        instance.setBlock(10, 41, 8, Block.CHEST);
        final TestConnection connection = env.createConnection();
        final Player player = connection.connect(instance, SPAWN);
        final TestConnection neighborConnection = env.createConnection();
        final Player neighbor = neighborConnection.connect(instance, new Pos(10.5, 40, 10.5));
        assertTrue(player.isViewer(neighbor), "the second player sees the digger");
        final Collector<BlockEntityDataPacket> toTheNeighbor = neighborConnection.trackIncoming(BlockEntityDataPacket.class);
        final Collector<ServerPacket> toTheDigger = connection.trackIncoming();

        dig(player, ClientPlayerActionPacket.Status.FINISHED_DIGGING, 39, 41, 8, 5);

        toTheNeighbor.assertEmpty();
        toTheDigger.assertSingle(AcknowledgeBlockChangePacket.class, ack -> assertEquals(5, ack.sequence()));
        assertTrue(instance.getBlock(39, 41, 8).compare(Block.CHEST), "the chest 30.5 blocks away is still there");

        player.setGameMode(GameMode.ADVENTURE);
        final Collector<BlockEntityDataPacket> refused = neighborConnection.trackIncoming(BlockEntityDataPacket.class);
        final Collector<BlockEntityDataPacket> refreshed = connection.trackIncoming(BlockEntityDataPacket.class);
        dig(player, ClientPlayerActionPacket.Status.FINISHED_DIGGING, 10, 41, 8, 6);
        refused.assertEmpty();
        refreshed.assertSingle(packet -> assertEquals(new Vec(10, 41, 8), packet.blockPosition().asVec()));
    }

    /**
     * A dig the world refuses, a read-only chunk as on a map shard, has the data of the block entity
     * sent again to the digger alone, behind the acknowledgement: repeating the dig costs one packet
     * in and two out, however many players watch the digger.
     */
    @Test
    public void aDigTheWorldRefusesRefreshesTheDiggerAlone(Env env) {
        final Instance instance = env.createFlatInstance();
        instance.setBlock(10, 41, 8, Block.CHEST);
        final Chunk chunk = Objects.requireNonNull(instance.getChunkAt(10, 8));
        chunk.lockWriteLock();
        try {
            chunk.setReadOnly(true);
        } finally {
            chunk.unlockWriteLock();
        }
        final TestConnection connection = env.createConnection();
        final Player player = connection.connect(instance, SPAWN);
        final TestConnection neighborConnection = env.createConnection();
        final Player neighbor = neighborConnection.connect(instance, new Pos(10.5, 40, 10.5));
        assertTrue(player.isViewer(neighbor), "the second player sees the digger");
        final Collector<BlockEntityDataPacket> toTheNeighbor = neighborConnection.trackIncoming(BlockEntityDataPacket.class);
        final Collector<ServerPacket> toTheDigger = connection.trackIncoming();

        dig(player, ClientPlayerActionPacket.Status.FINISHED_DIGGING, 10, 41, 8, 9);

        assertTrue(instance.getBlock(10, 41, 8).compare(Block.CHEST), "the read-only chunk lost its chest");
        toTheNeighbor.assertEmpty();
        assertEquals(List.of(AcknowledgeBlockChangePacket.class, BlockEntityDataPacket.class),
                toTheDigger.collect().stream().map(Object::getClass).toList());
    }

    /**
     * Editing a sign is a block use like the others: out of reach the edit is dropped before any
     * listener sees it.
     */
    @Test
    public void aSignEditOutOfReachIsDropped(Env env) {
        final Instance instance = env.createFlatInstance();
        instance.loadChunk(2, 0).join();
        // At eye height: 30.5 blocks east of the eyes, and 1.5
        instance.setBlock(39, 41, 8, Block.OAK_SIGN);
        instance.setBlock(10, 41, 8, Block.OAK_SIGN);
        final Player player = env.createConnection().connect(instance, SPAWN);
        final Collector<PlayerEditSignEvent> farEdits = env.trackEvent(PlayerEditSignEvent.class, EventFilter.PLAYER, player);
        send(player, new ClientUpdateSignPacket(new Vec(39, 41, 8), true, List.of("a", "b", "c", "d")));
        farEdits.assertEmpty();

        final Collector<PlayerEditSignEvent> nearEdits = env.trackEvent(PlayerEditSignEvent.class, EventFilter.PLAYER, player);
        send(player, new ClientUpdateSignPacket(new Vec(10, 41, 8), true, List.of("a", "b", "c", "d")));
        nearEdits.assertSingle(event -> assertEquals(new Vec(10, 41, 8), event.getBlockPosition().asVec()));
    }

    /** The limit is the block interaction range plus one block, exclusive, the range read from the attribute. */
    @Test
    public void theReachIsTheBlockInteractionRangePlusOneBlock(Env env) {
        final Instance instance = env.createFlatInstance();
        // At eye height, from the eyes at x 0.51: the cube at x 6 is 5.49 blocks away, at x -6 5.51, at x 10 9.49
        instance.setBlock(6, 41, 0, Block.STONE);
        instance.setBlock(-6, 41, 0, Block.STONE);
        instance.setBlock(10, 41, 0, Block.STONE);
        final Player player = env.createConnection().connect(instance, new Pos(0.51, 40, 0.5));

        dig(player, ClientPlayerActionPacket.Status.FINISHED_DIGGING, 6, 41, 0, 1);
        assertEquals(Block.AIR, instance.getBlock(6, 41, 0), "5.49 blocks away, range 4.5");
        dig(player, ClientPlayerActionPacket.Status.FINISHED_DIGGING, -6, 41, 0, 2);
        assertEquals(Block.STONE, instance.getBlock(-6, 41, 0), "5.51 blocks away, range 4.5");
        dig(player, ClientPlayerActionPacket.Status.FINISHED_DIGGING, 10, 41, 0, 3);
        assertEquals(Block.STONE, instance.getBlock(10, 41, 0), "9.49 blocks away, range 4.5");
        player.getAttribute(Attribute.BLOCK_INTERACTION_RANGE).setBaseValue(10);
        dig(player, ClientPlayerActionPacket.Status.FINISHED_DIGGING, 10, 41, 0, 4);
        assertEquals(Block.AIR, instance.getBlock(10, 41, 0), "9.49 blocks away, range 10");
    }

    /** In creative mode a STARTED_DIGGING alone breaks the block; the same reach applies. */
    @Test
    public void aCreativeInstantBreakOutOfReachBreaksNothing(Env env) {
        final Instance instance = env.createFlatInstance();
        final Player player = env.createConnection().connect(instance, SPAWN);
        player.setGameMode(GameMode.CREATIVE);

        dig(player, ClientPlayerActionPacket.Status.STARTED_DIGGING, 20, 39, 8, 1);
        assertEquals(Block.STONE, instance.getBlock(20, 39, 8), "11.61 blocks from the eyes");
        dig(player, ClientPlayerActionPacket.Status.STARTED_DIGGING, 10, 39, 8, 2);
        assertEquals(Block.AIR, instance.getBlock(10, 39, 8), "2.2 blocks from the eyes");
    }

    /**
     * A block use out of reach is refused before any interaction, and the client is told the blocks
     * as they are: the one it clicked and the one on the clicked face, where it predicted its block.
     */
    @Test
    public void aBlockUseOutOfReachIsRefused(Env env) {
        final Instance instance = env.createFlatInstance();
        final TestConnection connection = env.createConnection();
        final Player player = connection.connect(instance, SPAWN);
        player.setItemInMainHand(ItemStack.of(Material.STONE, 64));
        final Collector<PlayerBlockInteractEvent> interactions = env.trackEvent(PlayerBlockInteractEvent.class, EventFilter.PLAYER, player);
        final Collector<BlockChangePacket> blockChanges = connection.trackIncoming(BlockChangePacket.class);
        final Collector<AcknowledgeBlockChangePacket> acks = connection.trackIncoming(AcknowledgeBlockChangePacket.class);

        // The top face of the stone at (20, 39, 8), 11.61 blocks from the eyes
        send(player, new ClientPlayerBlockPlacementPacket(PlayerHand.MAIN, new Vec(20, 39, 8), BlockFace.TOP,
                0.5f, 1f, 0.5f, false, false, 3));

        assertEquals(Block.AIR, instance.getBlock(20, 40, 8), "placed 11.61 blocks from the eyes");
        interactions.assertEmpty();
        blockChanges.assertCount(2);
        acks.assertSingle(ack -> assertEquals(3, ack.sequence()));

        // The top face of the stone at (10, 39, 8), 2.2 blocks from the eyes
        send(player, new ClientPlayerBlockPlacementPacket(PlayerHand.MAIN, new Vec(10, 39, 8), BlockFace.TOP,
                0.5f, 1f, 0.5f, false, false, 4));
        assertEquals(Block.STONE, instance.getBlock(10, 40, 8), "placed 2.2 blocks from the eyes");
    }
}
