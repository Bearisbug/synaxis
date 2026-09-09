package dev.gameengine.entity.player;

import dev.gameengine.component.DataComponents;
import dev.gameengine.coordinate.Pos;
import dev.gameengine.entity.GameMode;
import dev.gameengine.entity.Player;
import dev.gameengine.entity.PlayerHand;
import dev.gameengine.event.EventFilter;
import dev.gameengine.event.player.PlayerBlockInteractEvent;
import dev.gameengine.instance.WorldBorder;
import dev.gameengine.instance.block.Block;
import dev.gameengine.instance.block.BlockFace;
import dev.gameengine.instance.block.BlockKeys;
import dev.gameengine.instance.block.predicate.BlockPredicate;
import dev.gameengine.instance.block.predicate.PropertiesPredicate;
import dev.gameengine.item.ItemStack;
import dev.gameengine.item.Material;
import dev.gameengine.item.component.BlockPredicates;
import dev.gameengine.network.packet.client.play.ClientPlayerBlockPlacementPacket;
import dev.gameengine.network.packet.server.play.AcknowledgeBlockChangePacket;
import dev.gameengine.network.packet.server.play.BlockChangePacket;
import dev.gameengine.registry.RegistryTag;
import dev.gameengine.testing.Env;
import dev.gameengine.testing.EnvTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class PlayerBlockPlacementIntegrationTest {

    @ParameterizedTest
    @MethodSource("placeBlockFromAdventureModeParams")
    public void placeBlockFromAdventureMode(Block baseBlock, BlockPredicates canPlaceOn, Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));

        instance.setBlock(2, 41, 0, baseBlock);

        player.setGameMode(GameMode.ADVENTURE);
        player.setItemInMainHand(ItemStack.builder(Material.WHITE_WOOL).set(DataComponents.CAN_PLACE_ON, canPlaceOn).build());

        var packet = new ClientPlayerBlockPlacementPacket(
                PlayerHand.MAIN, new Pos(2, 41, 0), BlockFace.WEST,
                1f, 1f, 1f,
                false, false, 0
        );
        player.addPacketToQueue(packet);
        player.interpretPacketQueue();

        var placedBlock = instance.getBlock(1, 41, 0);
        assertEquals("minecraft:white_wool", placedBlock.name());
    }

    @Test
    public void placeAgainstReplaceableBlock(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        player.setItemInMainHand(ItemStack.of(Material.WHITE_WOOL, 64));

        // clicking a replaceable block places IN PLACE, like vanilla and the client's prediction
        instance.setBlock(2, 41, 0, Block.SHORT_GRASS);
        placeAgainst(player, new Pos(2, 41, 0), BlockFace.WEST);
        assertEquals(Block.WHITE_WOOL, instance.getBlock(2, 41, 0), "the replaceable block is replaced in place");
        assertTrue(instance.getBlock(1, 41, 0).air(), "nothing lands on the clicked face's neighbor");

        // a non-replaceable clicked block still places against the face
        placeAgainst(player, new Pos(2, 41, 0), BlockFace.WEST);
        assertEquals(Block.WHITE_WOOL, instance.getBlock(1, 41, 0));
    }

    private static void placeAgainst(Player player, Pos clicked, BlockFace face) {
        player.addPacketToQueue(new ClientPlayerBlockPlacementPacket(
                PlayerHand.MAIN, clicked, face,
                1f, 1f, 1f,
                false, false, 0));
        player.interpretPacketQueue();
    }

    @Test
    public void placeAgainstBlockOutsideWorldBorderIsDenied(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        // The border spans [-1.5, 1.5), so the clicked block at x=2 is outside it.
        instance.setWorldBorder(new WorldBorder(3, 0, 0, 0, 0));
        instance.setBlock(2, 41, 0, Block.STONE);
        player.setItemInMainHand(ItemStack.of(Material.WHITE_WOOL));

        var interactions = env.trackEvent(PlayerBlockInteractEvent.class, EventFilter.PLAYER, player);
        var blockChanges = connection.trackIncoming(BlockChangePacket.class);
        var acks = connection.trackIncoming(AcknowledgeBlockChangePacket.class);
        var packet = new ClientPlayerBlockPlacementPacket(
                PlayerHand.MAIN, new Pos(2, 41, 0), BlockFace.WEST,
                1f, 1f, 1f,
                false, false, 0);
        player.addPacketToQueue(packet);
        player.interpretPacketQueue();

        assertTrue(instance.getBlock(1, 41, 0).air());
        // The denial happens before interaction handling and resyncs both predicted positions.
        interactions.assertCount(0);
        blockChanges.assertCount(2);
        acks.assertCount(1);
    }

    @Test
    public void placeIntoUnloadedChunkIsIgnored(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(8, 42, 8));
        // Chunk (100, 0) is loaded explicitly, its east neighbor is not.
        instance.loadChunk(100, 0).join();
        instance.setBlock(1615, 41, 5, Block.STONE);
        player.setItemInMainHand(ItemStack.of(Material.WHITE_WOOL));

        var acks = connection.trackIncoming(AcknowledgeBlockChangePacket.class);
        var packet = new ClientPlayerBlockPlacementPacket(
                PlayerHand.MAIN, new Pos(1615, 41, 5), BlockFace.EAST,
                1f, 1f, 1f,
                false, false, 0);
        player.addPacketToQueue(packet);
        player.interpretPacketQueue();

        // The placement lands in the unloaded chunk and is ignored, but the sequence is still acked.
        acks.assertCount(1);
    }

    @Test
    public void placeOutwardAcrossWorldBorderIsAllowed(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        // The border spans [-1.5, 1.5), so the clicked block at x=1 is inside it and the placement at x=2 is not.
        instance.setWorldBorder(new WorldBorder(3, 0, 0, 0, 0));
        instance.setBlock(1, 41, 0, Block.STONE);
        player.setItemInMainHand(ItemStack.of(Material.WHITE_WOOL));

        var packet = new ClientPlayerBlockPlacementPacket(
                PlayerHand.MAIN, new Pos(1, 41, 0), BlockFace.EAST,
                1f, 1f, 1f,
                false, false, 0);
        player.addPacketToQueue(packet);
        player.interpretPacketQueue();

        assertEquals("minecraft:white_wool", instance.getBlock(2, 41, 0).name());
    }

    private static Stream<Arguments> placeBlockFromAdventureModeParams() {
        return Stream.of(
                Arguments.of(Block.ACACIA_STAIRS.withProperty("facing", "south"), new BlockPredicates(new BlockPredicate(Block.ACACIA_STAIRS))),
                Arguments.of(Block.ACACIA_STAIRS.withProperty("facing", "south"),
                        new BlockPredicates(new BlockPredicate(RegistryTag.direct(BlockKeys.ACACIA_STAIRS),
                                PropertiesPredicate.exact("facing", "south"), null))),
                Arguments.of(Block.AMETHYST_BLOCK, new BlockPredicates(new BlockPredicate(Block.AMETHYST_BLOCK)))
        );
    }

}
