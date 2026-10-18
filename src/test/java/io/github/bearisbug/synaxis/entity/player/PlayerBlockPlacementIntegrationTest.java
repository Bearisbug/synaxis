package io.github.bearisbug.synaxis.entity.player;

import net.kyori.adventure.key.Key;
import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.coordinate.BlockVec;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.GameMode;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.entity.PlayerHand;
import io.github.bearisbug.synaxis.event.EventFilter;
import io.github.bearisbug.synaxis.event.player.PlayerBlockInteractEvent;
import io.github.bearisbug.synaxis.instance.WorldBorder;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.coordinate.BlockFace;
import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.instance.block.BlockKeys;
import io.github.bearisbug.synaxis.instance.block.predicate.BlockPredicate;
import io.github.bearisbug.synaxis.instance.block.predicate.PropertiesPredicate;
import io.github.bearisbug.synaxis.instance.block.rule.BlockPlacementRule;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.item.component.BlockPredicates;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerBlockPlacementPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.AcknowledgeBlockChangePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.BlockChangePacket;
import io.github.bearisbug.synaxis.registry.RegistryTag;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
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
        // Chunk (100, 0) is loaded explicitly, its east neighbor is not: the instance loads no chunk itself.
        instance.enableAutoChunkLoad(false);
        instance.loadChunk(100, 0).join();
        instance.setBlock(1615, 41, 5, Block.STONE);
        var connection = env.createConnection();
        // Within reach of the clicked block, which a block use out of reach never gets to
        var player = connection.connect(instance, new Pos(1612.5, 42, 5.5));
        player.setItemInMainHand(ItemStack.of(Material.WHITE_WOOL));

        var blockChanges = connection.trackIncoming(BlockChangePacket.class);
        var acks = connection.trackIncoming(AcknowledgeBlockChangePacket.class);
        var packet = new ClientPlayerBlockPlacementPacket(
                PlayerHand.MAIN, new Pos(1615, 41, 5), BlockFace.EAST,
                1f, 1f, 1f,
                false, false, 0);
        player.addPacketToQueue(packet);
        player.interpretPacketQueue();

        // The placement lands in the unloaded chunk and is ignored, but the sequence is still acked.
        acks.assertCount(1);
        // No block written back: not the refusal of a block use out of reach or outside the border
        blockChanges.assertCount(0);
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

    @Test
    public void aPlacementThePlacementRuleCancelsKeepsTheBlockAndTheItem(Env env) {
        env.process().block().registerBlockPlacementRule(new BlockPlacementRule(Block.STONE) {
            @Override
            public @Nullable Block blockPlace(PlacementState placementState) {
                return null;
            }
        });
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        instance.setBlock(2, 41, 0, Block.SHORT_GRASS);
        player.setItemInMainHand(ItemStack.of(Material.STONE));
        var blockChanges = connection.trackIncoming(BlockChangePacket.class);
        var acks = connection.trackIncoming(AcknowledgeBlockChangePacket.class);

        // A survival player replaces the grass in place, and the rule cancels it
        placeAgainst(player, new Pos(2, 41, 0), BlockFace.WEST);

        assertEquals(Block.SHORT_GRASS, instance.getBlock(2, 41, 0), "the grass is left as it is");
        assertEquals(ItemStack.of(Material.STONE), player.getItemInMainHand(), "the stone is not used up");
        // The client predicted the stone, the rollback writes the grass back
        blockChanges.assertSingle(change -> {
            assertEquals(new BlockVec(2, 41, 0), change.blockPosition().asBlockVec());
            assertEquals(Block.SHORT_GRASS.stateId(), change.blockStateId());
        });
        acks.assertCount(1);
    }

    @Test
    public void thePlayerPlacementHandedToTheHandlerHasTheBlockThePlacementRuleChose(Env env) {
        final AtomicReference<BlockHandler.Placement> placements = new AtomicReference<>();
        final BlockHandler handler = new BlockHandler() {
            @Override
            public void onPlace(Placement placement) {
                placements.set(placement);
            }

            @Override
            public Key getKey() {
                return Key.key("synaxis:test");
            }
        };
        env.process().block().registerBlockPlacementRule(new BlockPlacementRule(Block.FURNACE) {
            @Override
            public @Nullable Block blockPlace(PlacementState placementState) {
                return placementState.block().withProperty("facing", "east").withHandler(handler);
            }
        });
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        instance.setBlock(2, 41, 0, Block.STONE);
        player.setItemInMainHand(ItemStack.of(Material.FURNACE));

        placeAgainst(player, new Pos(2, 41, 0), BlockFace.WEST);

        final Block placed = instance.getBlock(1, 41, 0);
        assertEquals("east", placed.getProperty("facing"));
        final var placement = assertInstanceOf(BlockHandler.PlayerPlacement.class, placements.get());
        assertEquals(placed, placement.getBlock(), "the block the handler is placed with");
        assertSame(player, placement.getPlayer());
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
