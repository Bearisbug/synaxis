package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.collision.CollisionUtils;
import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.GameMode;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.entity.PlayerHand;
import io.github.bearisbug.synaxis.event.player.PlayerBlockInteractEvent;
import io.github.bearisbug.synaxis.event.player.PlayerBlockPlaceEvent;
import io.github.bearisbug.synaxis.event.player.PlayerUseItemOnBlockEvent;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.coordinate.BlockFace;
import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.instance.block.rule.BlockPlacementRule;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.item.component.BlockPredicates;
import io.github.bearisbug.synaxis.item.component.ItemBlockState;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerBlockPlacementPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.AcknowledgeBlockChangePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.BlockChangePacket;
import io.github.bearisbug.synaxis.instance.ChunkUtils;
import io.github.bearisbug.synaxis.inventory.PlayerInventoryUtils;
import io.github.bearisbug.synaxis.world.DimensionType;

public class BlockPlacementListener {
    public static void listener(ClientPlayerBlockPlacementPacket packet, Player player) {
        final PlayerHand hand = packet.hand();
        final BlockFace blockFace = packet.blockFace();
        Point blockPosition = packet.blockPosition();

        final Instance instance = player.getInstance();
        if (instance == null) {
            player.sendPacket(new AcknowledgeBlockChangePacket(packet.sequence()));
            return;
        }

        // Prevent outdated/modified client data
        final Chunk interactedChunk = instance.getChunkAt(blockPosition);
        if (!ChunkUtils.isLoaded(interactedChunk)) {
            // Client tried to place a block in an unloaded chunk; ignore the request but still ack to reset the client prediction
            refreshUsedHandSlot(player, hand);
            player.sendPacket(new AcknowledgeBlockChangePacket(packet.sequence()));
            return;
        }

        final ItemStack usedItem = player.getItemInHand(hand);
        final Block interactedBlock = instance.getBlock(blockPosition);

        // Any use of a block outside the world border or out of the player's reach is rejected before
        // interaction handling. The resulting placement position is intentionally not checked as placing
        // outward across the border by clicking a block inside of it is allowed.
        if (!instance.getWorldBorder().inBounds(blockPosition) || PlayerActionListener.outOfReach(player, blockPosition)) {
            final Point relative = blockPosition.relative(blockFace);
            if (ChunkUtils.isLoaded(instance.getChunkAt(relative))) {
                player.sendPacket(new BlockChangePacket(relative, instance.getBlock(relative)));
            }
            rollback(player, hand, blockPosition, interactedBlock, packet.sequence());
            return;
        }

        final Point cursorPosition = new Vec(packet.cursorPositionX(), packet.cursorPositionY(), packet.cursorPositionZ());

        // Interact at block
        // FIXME: onUseOnBlock
        PlayerBlockInteractEvent playerBlockInteractEvent = new PlayerBlockInteractEvent(player, hand, instance, interactedBlock, blockPosition.asBlockVec(), cursorPosition, blockFace);
        player.process().eventHandler().call(playerBlockInteractEvent);
        boolean blockUse = playerBlockInteractEvent.isBlockingItemUse();
        if (!playerBlockInteractEvent.isCancelled()) {
            final var handler = interactedBlock.handler();
            if (handler != null) {
                blockUse |= !handler.onInteract(new BlockHandler.Interaction(interactedBlock, instance, blockFace, blockPosition, cursorPosition, player, hand));
            }
        }
        if (blockUse) {
            // If the usage was blocked then the world is already up-to-date (from the prior handlers),
            // So ack the change with the current world state.
            refreshUsedHandSlot(player, hand);
            player.sendPacket(new AcknowledgeBlockChangePacket(packet.sequence()));
            return;
        }

        final Material useMaterial = usedItem.material();
        if (useMaterial.block() == null) {
            // Player didn't try to place a block but interacted with one
            PlayerUseItemOnBlockEvent event = new PlayerUseItemOnBlockEvent(player, hand, usedItem, blockPosition, cursorPosition, blockFace);
            player.process().eventHandler().call(event);
            // Ack the block change. This is required to reset the client prediction to the server state.
            player.sendPacket(new AcknowledgeBlockChangePacket(packet.sequence()));
            return;
        }

        // Verify if the player can place the block
        boolean canPlaceBlock = true;
        // Check if the player is allowed to place blocks based on their game mode
        if (player.getGameMode() == GameMode.SPECTATOR) {
            canPlaceBlock = false; // Spectators can't place blocks
        } else if (player.getGameMode() == GameMode.ADVENTURE) {
            //Check if the block can be placed on the block
            BlockPredicates placePredicate = usedItem.get(DataComponents.CAN_PLACE_ON, BlockPredicates.NEVER);
            canPlaceBlock = placePredicate.test(instance.registries(), interactedBlock);
        }


        // Get the newly placed block position
        //todo it feels like it should be possible to have better replacement rules than this, feels pretty scuffed.
        Point placementPosition = blockPosition;
        var interactedPlacementRule = player.process().block().getBlockPlacementRule(interactedBlock);
        // A replaceable clicked block (grass, snow layers, water) is replaced in place, like vanilla
        // and the client's own prediction; the same check the neighbor position gets below
        final boolean placeAdjacent = !interactedBlock.air() && !interactedBlock.replaceable()
                && (interactedPlacementRule == null || !interactedPlacementRule.isSelfReplaceable(
                new BlockPlacementRule.Replacement(interactedBlock, blockFace, cursorPosition, false, useMaterial)));
        // If the block is not replaceable, try to place next to it.
        if (placeAdjacent) placementPosition = blockPosition.relative(blockFace);

        final Chunk chunk = instance.getChunkAt(placementPosition);
        if (!ChunkUtils.isLoaded(chunk)) {
            // Placement into an unloaded chunk, reachable by placing against a block at the edge of loaded terrain.
            refreshUsedHandSlot(player, hand);
            player.sendPacket(new AcknowledgeBlockChangePacket(packet.sequence()));
            return;
        }

        if (placeAdjacent) {
            var placementBlock = instance.getBlock(placementPosition);
            var placementRule = player.process().block().getBlockPlacementRule(placementBlock);
            if (!placementBlock.replaceable() && !(placementRule != null && placementRule.isSelfReplaceable(
                    new BlockPlacementRule.Replacement(placementBlock, blockFace, cursorPosition, true, useMaterial)))) {
                // If the block is still not replaceable, cancel the placement
                canPlaceBlock = false;
            }
        }

        final DimensionType instanceDim = instance.getCachedDimensionType();
        if (placementPosition.y() >= instanceDim.maxY() || placementPosition.y() < instanceDim.minY()) {
            // Placement outside the world's build height (reachable by placing against the top/bottom block); ack to reset the prediction
            player.sendPacket(new AcknowledgeBlockChangePacket(packet.sequence()));
            return;
        }

        if (!canPlaceBlock) {
            // Keep the client in sync with a targeted block change (plus a held-slot refresh) instead of a chunk resend,
            // which leaves the client desynced after rapid invalid block placements.
            rollback(player, hand, placementPosition, instance.getBlock(placementPosition), packet.sequence());
            return;
        }

        if (chunk.isReadOnly()) {
            rollback(player, hand, placementPosition, instance.getBlock(placementPosition), packet.sequence());
            return;
        }

        final ItemBlockState blockState = usedItem.get(DataComponents.BLOCK_STATE, ItemBlockState.EMPTY);
        final Block placedBlock = blockState.apply(useMaterial.block());

        Entity collisionEntity = CollisionUtils.canPlaceBlockAt(instance, placementPosition, placedBlock);
        if (collisionEntity != null) {
            // If a player is trying to place a block on themselves, the client sends a block change but does not set
            // the block on its own client, so it only needs an acknowledgement.
            if (collisionEntity == player) {
                player.sendPacket(new AcknowledgeBlockChangePacket(packet.sequence()));
                return;
            }
            // Otherwise correct the block where the server and client bounding boxes differ, with a targeted block change instead of a chunk resend.
            rollback(player, hand, placementPosition, instance.getBlock(placementPosition), packet.sequence());
            return;
        }

        // BlockPlaceEvent check
        PlayerBlockPlaceEvent playerBlockPlaceEvent = new PlayerBlockPlaceEvent(player, instance, placedBlock, blockFace, placementPosition.asBlockVec(), cursorPosition, packet.hand());
        playerBlockPlaceEvent.consumeBlock(player.getGameMode() != GameMode.CREATIVE);
        playerBlockPlaceEvent.setDoBlockUpdates(blockState.equals(useMaterial.prototype().get(DataComponents.BLOCK_STATE, ItemBlockState.EMPTY)));
        player.process().eventHandler().call(playerBlockPlaceEvent);
        if (playerBlockPlaceEvent.isCancelled()) {
            rollback(player, hand, placementPosition, instance.getBlock(placementPosition), packet.sequence());
            return;
        }

        // Place the block
        Block resultBlock = playerBlockPlaceEvent.getBlock();
        if (!instance.placeBlock(new BlockHandler.PlayerPlacement(resultBlock, instance.getBlock(placementPosition), instance, placementPosition, player, hand, blockFace,
                packet.cursorPositionX(), packet.cursorPositionY(), packet.cursorPositionZ()), playerBlockPlaceEvent.shouldDoBlockUpdates())) {
            // Nothing was placed, e.g. the placement rule cancelled it: nothing is used up
            rollback(player, hand, placementPosition, instance.getBlock(placementPosition), packet.sequence());
            return;
        }
        player.sendPacket(new AcknowledgeBlockChangePacket(packet.sequence()));
        // Block consuming, unless a listener changed what the hand holds (an equal copy is the same item)
        if (playerBlockPlaceEvent.doesConsumeBlock() && player.getItemInHand(hand).equals(usedItem)) {
            // Consume the block in the player's hand
            final ItemStack newUsedItem = usedItem.consume(1);
            player.setItemInHand(hand, newUsedItem);
        } else {
            // Prevent invisible item on client: it predicted a decrement that didn't happen, so refresh just that slot.
            refreshUsedHandSlot(player, hand);
        }
    }

    // Corrects a rejected placement with a targeted block change instead of resending the whole chunk, and refreshes
    // only the used hand slot so the client's predicted item count stays in sync without a full inventory resend.
    private static void rollback(Player player, PlayerHand hand, Point placementPosition, Block block, int sequence) {
        refreshUsedHandSlot(player, hand);
        player.sendPacket(new BlockChangePacket(placementPosition, block));
        player.sendPacket(new AcknowledgeBlockChangePacket(sequence));
    }

    // Refreshes only the used hand slot so the client's predicted item count stays in sync without a full inventory resend.
    private static void refreshUsedHandSlot(Player player, PlayerHand hand) {
        final int slot = hand == PlayerHand.OFF ? PlayerInventoryUtils.OFFHAND_SLOT : player.getHeldSlot();
        player.getInventory().sendSlotRefresh(slot, player.getItemInHand(hand));
    }
}
