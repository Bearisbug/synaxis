package io.github.bearisbug.synaxis.app.store;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * The value of the host's player section {@code synaxis:player} ({@code docs/DESIGN-PROD.md} {@code API-PROD-032}):
 * where the player was and in which world, the 46 slots of its inventory, the held slot and its health.
 *
 * @param world     the world the position belongs to; null until the state is captured for the first time
 * @param position  the position with its yaw and pitch; null until then
 * @param inventory the slots 0..45 of the {@code PlayerInventory} layout that hold something, without air
 * @param heldSlot  the held hotbar slot, 0..8
 * @param health    the health, 0 for a player that left on the death screen
 */
public record PlayerState(@Nullable World world, @Nullable Pos position, Map<Integer, ItemStack> inventory,
                          int heldSlot, float health) {

    /** Which world a saved position belongs to. */
    public sealed interface World permits Core, Gameplay, MapShard {
    }

    /** The flat core world. */
    public record Core() implements World {
    }

    /** The world of a gameplay plugin. */
    public record Gameplay() implements World {
    }

    /**
     * A map shard's world.
     *
     * @param mapId   the map id of its map release
     * @param buildId the build id of its map release
     */
    public record MapShard(String mapId, String buildId) implements World {
    }

    /** A character nothing was ever captured for. */
    public static final PlayerState DEFAULT = new PlayerState(null, null, Map.of(), 0, 20f);

    public PlayerState {
        inventory = Map.copyOf(inventory);
    }

    /**
     * Whether this state was never captured, which is what makes a character new.
     *
     * @return true for {@link #DEFAULT} and every state without a world
     */
    public boolean isDefault() {
        return world == null;
    }
}
