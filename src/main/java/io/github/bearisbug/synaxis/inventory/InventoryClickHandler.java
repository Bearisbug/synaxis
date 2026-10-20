package io.github.bearisbug.synaxis.inventory;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.inventory.InventoryClickEvent;
import io.github.bearisbug.synaxis.inventory.click.Click;
import io.github.bearisbug.synaxis.inventory.click.ClickType;
import io.github.bearisbug.synaxis.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/**
 * Represents an inventory which can receive click input.
 * All methods returning boolean returns true if the action is successful, false otherwise.
 * <p>
 * See <a href="https://minecraft.wiki/w/Minecraft_Wiki:Projects/wiki.vg_merge/Protocol#Click_Container">the Minecraft wiki</a> for more information.
 * <p>
 * The callbacks are called by {@link AbstractInventory#handleClick(Player, Click)}, which holds the monitor of the
 * clicked inventory and then the one of the clicking player's {@link PlayerInventory}, and dispatches the events the
 * click raises once it has been committed. To simulate a click, call {@link #handleClick(Player, Click)} rather than a
 * callback; an override of a callback must not lock another inventory. {@code handleClick} does not dispatch
 * {@link io.github.bearisbug.synaxis.event.inventory.InventoryPreClickEvent}: only the engine's listener of the click
 * packet does.
 */
public sealed interface InventoryClickHandler permits AbstractInventory {

    /**
     * Parses a click. This delegates to each individual implementation method.
     *
     * @param player the player who clicked
     * @param click  the click that occurred
     * @return whether the click was a success
     */
    default boolean handleClick(Player player, Click click) {
        // Maps a click back onto the callbacks of this interface
        return switch (click) {
            case Click.Left(int slot) -> leftClick(player, slot);
            case Click.Right(int slot) -> rightClick(player, slot);
            case Click.Middle(int slot) -> middleClick(player, slot);

            case Click.LeftShift(int slot) -> shiftClick(player, slot, 0);
            case Click.RightShift(int slot) -> shiftClick(player, slot, 1);

            case Click.Double(int slot) -> doubleClick(player, slot);

            case Click.LeftDrag(List<Integer> slots) -> dragging(player, slots, 2);
            case Click.RightDrag(List<Integer> slots) -> dragging(player, slots, 6);
            case Click.MiddleDrag(List<Integer> slots) -> dragging(player, slots, 10);

            case Click.LeftDropCursor() -> drop(player, true, -999);
            case Click.RightDropCursor() -> drop(player, false, -999);
            case Click.MiddleDropCursor() -> false; // Does nothing currently

            case Click.DropSlot(int slot, boolean all) -> drop(player, all, slot);

            case Click.HotbarSwap(int hotbarSlot, int slot) -> changeHeld(player, slot, hotbarSlot);

            case Click.OffhandSwap(int slot) -> changeHeld(player, slot, PlayerInventoryUtils.OFFHAND_SLOT);
        };
    }

    /**
     * Called when a {@link Player} left click in the inventory.
     *
     * @param player the player who clicked
     * @param slot   the slot number
     * @return true if the click hasn't been cancelled, false otherwise
     */
    @ApiStatus.OverrideOnly
    boolean leftClick(Player player, int slot);

    /**
     * Called when a {@link Player} right click in the inventory.
     *
     * @param player the player who clicked
     * @param slot   the slot number
     * @return true if the click hasn't been cancelled, false otherwise
     */
    @ApiStatus.OverrideOnly
    boolean rightClick(Player player, int slot);

    /**
     * Called when a {@link Player} shift click in the inventory
     *
     * @param player the player who clicked
     * @param slot   the slot number
     * @param button the button (same behaviour in vanilla, but can be used for custom behaviour)
     * @return true if the click hasn't been cancelled, false otherwise
     */
    @ApiStatus.OverrideOnly
    boolean shiftClick(Player player, int slot, int button);

    /**
     * Called when a {@link Player} held click in the inventory
     *
     * @param player the player who clicked
     * @param slot   the slot number
     * @param key    the held slot (0-8) pressed
     * @return true if the click hasn't been cancelled, false otherwise
     */
    @ApiStatus.OverrideOnly
    boolean changeHeld(Player player, int slot, int key);

    /**
     * Called when a {@link Player} middle clicks in the inventory, a creative mode click.
     *
     * @param player the player who clicked
     * @param slot   the slot number
     * @return true if the click hasn't been cancelled, false otherwise
     */
    @ApiStatus.OverrideOnly
    boolean middleClick(Player player, int slot);

    /**
     * Called when a {@link Player} press the drop button
     *
     * @param player the player who clicked
     * @param all    true to drop the whole stack, false to drop a single item
     * @param slot   the slot number (-999 if clicking outside, i.e. dropping cursor)
     * @return true if the drop hasn't been cancelled, false otherwise
     */
    @ApiStatus.OverrideOnly
    boolean drop(Player player, boolean all, int slot);

    /**
     * Called when a {@link Player} ends a drag over slots of the inventory.
     *
     * @param player the player who dragged
     * @param slots  the slot numbers the drag went over
     * @param button the code the client ends the drag with, which {@link #handleClick(Player, Click)} maps from
     *               {@link Click.LeftDrag} (2), {@link Click.RightDrag} (6) and {@link Click.MiddleDrag} (10, creative
     *               mode only)
     * @return true if the drag hasn't been cancelled, false otherwise
     * @see <a href="https://minecraft.wiki/w/Minecraft_Wiki:Projects/wiki.vg_merge/Protocol#Click_Container">the Minecraft wiki</a>
     */
    @ApiStatus.OverrideOnly
    boolean dragging(Player player, List<Integer> slots, int button);

    /**
     * Called when a {@link Player} double click in the inventory
     *
     * @param player the player who clicked
     * @param slot   the slot number
     * @return true if the click hasn't been cancelled, false otherwise
     */
    @ApiStatus.OverrideOnly
    boolean doubleClick(Player player, int slot);

    default void callClickEvent(Player player, AbstractInventory inventory, int slot,
                                ClickType clickType, ItemStack clicked, ItemStack cursor) {
        AbstractInventory.dispatch(player.process(), new InventoryClickEvent(inventory, player, slot, clickType, clicked, cursor));
    }
}
