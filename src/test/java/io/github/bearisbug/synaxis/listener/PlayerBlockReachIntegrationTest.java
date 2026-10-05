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
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerActionPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerBlockPlacementPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.AcknowledgeBlockChangePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.BlockChangePacket;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        assertEquals(Block.STONE, instance.getBlock(20, 39, 8), "11.5 blocks from the eyes");
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

        // The top face of the stone at (20, 39, 8), 11.5 blocks from the eyes
        send(player, new ClientPlayerBlockPlacementPacket(PlayerHand.MAIN, new Vec(20, 39, 8), BlockFace.TOP,
                0.5f, 1f, 0.5f, false, false, 3));

        assertEquals(Block.AIR, instance.getBlock(20, 40, 8), "placed 11.5 blocks from the eyes");
        interactions.assertEmpty();
        blockChanges.assertCount(2);
        acks.assertSingle(ack -> assertEquals(3, ack.sequence()));

        // The top face of the stone at (10, 39, 8), 2.2 blocks from the eyes
        send(player, new ClientPlayerBlockPlacementPacket(PlayerHand.MAIN, new Vec(10, 39, 8), BlockFace.TOP,
                0.5f, 1f, 0.5f, false, false, 4));
        assertEquals(Block.STONE, instance.getBlock(10, 40, 8), "placed 2.2 blocks from the eyes");
    }
}
