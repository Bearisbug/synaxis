package io.github.bearisbug.synaxis.listener;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.entity.PlayerHand;
import io.github.bearisbug.synaxis.entity.attribute.Attribute;
import io.github.bearisbug.synaxis.entity.metadata.LivingEntityMeta;
import io.github.bearisbug.synaxis.event.item.PlayerCancelItemUseEvent;
import io.github.bearisbug.synaxis.event.player.PlayerCancelDiggingEvent;
import io.github.bearisbug.synaxis.event.player.PlayerFinishDiggingEvent;
import io.github.bearisbug.synaxis.event.player.PlayerStabEvent;
import io.github.bearisbug.synaxis.event.player.PlayerStartDiggingEvent;
import io.github.bearisbug.synaxis.event.player.PlayerSwapItemEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.coordinate.BlockFace;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.component.BlockPredicates;
import io.github.bearisbug.synaxis.item.component.Tool;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerActionPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.AcknowledgeBlockChangePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.BlockEntityDataPacket;
import io.github.bearisbug.synaxis.instance.block.BlockBreakCalculation;
import io.github.bearisbug.synaxis.instance.block.BlockUtils;

public final class PlayerActionListener {

    public static void playerActionListener(ClientPlayerActionPacket packet, Player player) {
        final ClientPlayerActionPacket.Status status = packet.status();
        final Point blockPosition = packet.blockPosition();
        final Instance instance = player.getInstance();
        if (instance == null) return;

        final boolean dig = status == ClientPlayerActionPacket.Status.STARTED_DIGGING
                || status == ClientPlayerActionPacket.Status.FINISHED_DIGGING;
        if (dig && instance.isChunkLoaded(blockPosition) && outOfReach(player, blockPosition)) {
            // Out of reach the vanilla server only acknowledges, on which the client rolls its prediction
            // back; the data of a block entity is not sent again, to the player or to its viewers
            player.sendPacket(new AcknowledgeBlockChangePacket(packet.sequence()));
            return;
        }

        DiggingResult diggingResult = null;
        if (status == ClientPlayerActionPacket.Status.STARTED_DIGGING) {
            if (!instance.isChunkLoaded(blockPosition)) return;
            diggingResult = startDigging(player, instance, blockPosition, packet.blockFace());
        } else if (status == ClientPlayerActionPacket.Status.CANCELLED_DIGGING) {
            if (!instance.isChunkLoaded(blockPosition)) return;
            diggingResult = cancelDigging(player, instance, blockPosition);
        } else if (status == ClientPlayerActionPacket.Status.FINISHED_DIGGING) {
            if (!instance.isChunkLoaded(blockPosition)) return;
            diggingResult = finishDigging(player, instance, blockPosition, packet.blockFace());
        } else if (status == ClientPlayerActionPacket.Status.DROP_ITEM_STACK) {
            dropStack(player);
        } else if (status == ClientPlayerActionPacket.Status.DROP_ITEM) {
            dropSingle(player);
        } else if (status == ClientPlayerActionPacket.Status.UPDATE_ITEM_STATE) {
            updateItemState(player);
        } else if (status == ClientPlayerActionPacket.Status.SWAP_ITEM_HAND) {
            swapItemHand(player);
        } else if (status == ClientPlayerActionPacket.Status.STAB) {
            stab(player);
        }
        // Acknowledge start/cancel/finish digging status
        if (diggingResult != null) {
            player.sendPacket(new AcknowledgeBlockChangePacket(packet.sequence()));
            if (!diggingResult.success()) {
                // Refresh block on player screen in case it had special data (like a sign)
                var blockEntityType = diggingResult.block().blockEntityType();
                if (blockEntityType != null) {
                    final CompoundBinaryTag data = BlockUtils.extractClientNbt(diggingResult.block());
                    player.sendPacketToViewersAndSelf(new BlockEntityDataPacket(blockPosition, blockEntityType, data));
                }
            }
        }
    }

    private static DiggingResult startDigging(Player player, Instance instance, Point blockPosition, BlockFace blockFace) {
        final Block block = instance.getBlock(blockPosition);

        // Prevent spectators and check players in adventure mode
        if (shouldPreventBreaking(player, block)) {
            return new DiggingResult(block, false);
        }

        final int breakTicks = BlockBreakCalculation.breakTicks(block, player);
        final boolean instantBreak = breakTicks == 0;
        if (!instantBreak) {
            PlayerStartDiggingEvent playerStartDiggingEvent = new PlayerStartDiggingEvent(player, instance, block, blockPosition.asBlockVec(), blockFace);
            player.process().eventHandler().call(playerStartDiggingEvent);
            return new DiggingResult(block, !playerStartDiggingEvent.isCancelled());
        }
        // Client only sends a single STARTED_DIGGING when insta-break is enabled
        return breakBlock(instance, player, blockPosition, block, blockFace);
    }

    private static DiggingResult cancelDigging(Player player, Instance instance, Point blockPosition) {
        final Block block = instance.getBlock(blockPosition);

        PlayerCancelDiggingEvent playerCancelDiggingEvent = new PlayerCancelDiggingEvent(player, instance, block, blockPosition.asBlockVec());
        player.process().eventHandler().call(playerCancelDiggingEvent);
        return new DiggingResult(block, true);
    }

    private static DiggingResult finishDigging(Player player, Instance instance, Point blockPosition, BlockFace blockFace) {
        final Block block = instance.getBlock(blockPosition);

        if (shouldPreventBreaking(player, block)) {
            return new DiggingResult(block, false);
        }

        final int breakTicks = BlockBreakCalculation.breakTicks(block, player);
        // Realistically shouldn't happen, but a hacked client can send any packet, also illegal ones
        // If the block is unbreakable, prevent a hacked client from breaking it!
        if (breakTicks == BlockBreakCalculation.UNBREAKABLE) {
            PlayerCancelDiggingEvent playerCancelDiggingEvent = new PlayerCancelDiggingEvent(player, instance, block, blockPosition.asBlockVec());
            player.process().eventHandler().call(playerCancelDiggingEvent);
            return new DiggingResult(block, false);
        }
        // TODO maybe add a check if the player has spent enough time mining the block.
        //   a hacked client could send START_DIGGING and FINISH_DIGGING to instamine any block

        PlayerFinishDiggingEvent playerFinishDiggingEvent = new PlayerFinishDiggingEvent(player, instance, block, blockPosition.asBlockVec());
        player.process().eventHandler().call(playerFinishDiggingEvent);

        return breakBlock(instance, player, blockPosition, playerFinishDiggingEvent.getBlock(), blockFace);
    }

    private static boolean shouldPreventBreaking(Player player, Block block) {
        final ItemStack itemInMainHand = player.getItemInMainHand();

        return switch (player.getGameMode()) {
            // Spectators can't break blocks
            case SPECTATOR -> true;
            // Check if the currently held item can break the block
            case ADVENTURE -> !itemInMainHand
                    .get(DataComponents.CAN_BREAK, BlockPredicates.NEVER)
                    .test(player.process().registries(), block);
            // Certain tools (swords, tridents, maces) can't break blocks in creative
            case CREATIVE -> {
                final Tool tool = itemInMainHand.get(DataComponents.TOOL);
                yield tool != null && !tool.canDestroyBlocksInCreative();
            }
            default -> false;
        };
    }

    /**
     * Whether the block at {@code blockPosition} is out of the player's reach, by the vanilla
     * server's rule for digging and using a block: the cube of the block is at least the player's
     * {@link Attribute#BLOCK_INTERACTION_RANGE} plus one block from its eyes. The client picks blocks
     * as far as the same attribute, which the engine sends it.
     */
    static boolean outOfReach(Player player, Point blockPosition) {
        final Pos position = player.getPosition();
        final double eyeY = position.y() + player.getEyeHeight();
        final int x = blockPosition.blockX(), y = blockPosition.blockY(), z = blockPosition.blockZ();
        final double dx = position.x() - Math.clamp(position.x(), x, x + 1);
        final double dy = eyeY - Math.clamp(eyeY, y, y + 1);
        final double dz = position.z() - Math.clamp(position.z(), z, z + 1);
        final double reach = player.getAttributeValue(Attribute.BLOCK_INTERACTION_RANGE) + 1;
        return dx * dx + dy * dy + dz * dz >= reach * reach;
    }

    private static void dropStack(Player player) {
        final ItemStack droppedItemStack = player.getItemInMainHand();
        dropItem(player, droppedItemStack, ItemStack.AIR);
    }

    private static void dropSingle(Player player) {
        final ItemStack handItem = player.getItemInMainHand();
        final int handAmount = handItem.amount();
        if (handAmount <= 1) {
            // Drop the whole item without copy
            dropItem(player, handItem, ItemStack.AIR);
        } else {
            // Drop a single item
            dropItem(player,
                    handItem.withAmount(1), // Single dropped item
                    handItem.withAmount(handAmount - 1)); // Updated hand
        }
    }

    private static void updateItemState(Player player) {
        LivingEntityMeta meta = player.getLivingEntityMeta();
        if (meta == null || !meta.isHandActive()) return;
        final PlayerHand hand = meta.getActiveHand();

        PlayerCancelItemUseEvent cancelUseEvent = new PlayerCancelItemUseEvent(player, hand, player.getItemInHand(hand), player.getCurrentItemUseTime());
        player.process().eventHandler().call(cancelUseEvent);

        // Reset server state
        final boolean isOffHand = hand == PlayerHand.OFF;
        player.refreshActiveHand(false, isOffHand, cancelUseEvent.isRiptideSpinAttack());
        player.clearItemUse();
    }

    private static void swapItemHand(Player player) {
        final ItemStack mainHand = player.getItemInMainHand();
        final ItemStack offHand = player.getItemInOffHand();
        PlayerSwapItemEvent swapItemEvent = new PlayerSwapItemEvent(player, offHand, mainHand);
        player.process().eventHandler().callCancellable(swapItemEvent, () -> {
            player.setItemInMainHand(swapItemEvent.getMainHandItem());
            player.setItemInOffHand(swapItemEvent.getOffHandItem());
        });
        // The client swaps locally before asking, so a refusal has to be sent back
        if (swapItemEvent.isCancelled()) player.getInventory().update();
    }

    private static DiggingResult breakBlock(Instance instance,
                                            Player player,
                                            Point blockPosition, Block previousBlock, BlockFace blockFace) {
        // Unverified block break, client is fully responsible
        final boolean success = instance.breakBlock(player, blockPosition, blockFace);
        final Block updatedBlock = instance.getBlock(blockPosition);
        if (!success) {
            if (previousBlock.solid()) {
                final Pos playerPosition = player.getPosition();
                // Teleport the player back if he broke a solid block just below him
                if (playerPosition.sub(0, 1, 0).samePoint(blockPosition)) {
                    var _ = player.teleport(playerPosition);
                }
            }
        }
        return new DiggingResult(updatedBlock, success);
    }

    private static void dropItem(Player player,
                                 ItemStack droppedItem, ItemStack handItem) {
        if (player.dropItem(droppedItem)) {
            player.setItemInMainHand(handItem);
        } else {
            player.getInventory().update();
        }
    }

    private static void stab(Player player) {
        final ItemStack itemInMainHand = player.getItemInMainHand();
        if (!itemInMainHand.has(DataComponents.PIERCING_WEAPON))
            return;
        player.process().eventHandler().call(new PlayerStabEvent(player));
    }

    private record DiggingResult(Block block, boolean success) {
    }
}
