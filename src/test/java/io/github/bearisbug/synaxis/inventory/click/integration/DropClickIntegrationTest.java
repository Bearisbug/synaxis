package io.github.bearisbug.synaxis.inventory.click.integration;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.item.ItemDropEvent;
import io.github.bearisbug.synaxis.inventory.AbstractInventory;
import io.github.bearisbug.synaxis.inventory.Inventory;
import io.github.bearisbug.synaxis.inventory.InventoryType;
import io.github.bearisbug.synaxis.inventory.click.Click;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A drop click takes its items from the cursor when it is outside the window (left click the stack, right click one
 * item) and from the slot under the drop key (Q one item, Ctrl + Q the stack), in a container as in the player's own
 * inventory. A cancelled {@link ItemDropEvent} leaves the cursor and the slot as they were.
 */
@EnvTest
public class DropClickIntegrationTest {

    private static final ItemStack CURSOR = ItemStack.of(Material.STONE, 10);
    private static final ItemStack SLOT = ItemStack.of(Material.DIAMOND, 5);
    // The clicked slot, in the container (two rows) as in the player's main inventory
    private static final int CLICKED = 9;

    /** The four drops and what each one drops and leaves on the cursor and in the slot, in both inventories. */
    static Stream<Arguments> drops() {
        return Stream.of(true, false).flatMap(inContainer -> Stream.of(
                Arguments.of(new Click.LeftDropCursor(), inContainer, CURSOR, ItemStack.AIR, SLOT),
                Arguments.of(new Click.RightDropCursor(), inContainer, CURSOR.withAmount(1), CURSOR.withAmount(9), SLOT),
                Arguments.of(new Click.DropSlot(CLICKED, false), inContainer, SLOT.withAmount(1), CURSOR, SLOT.withAmount(4)),
                Arguments.of(new Click.DropSlot(CLICKED, true), inContainer, SLOT, CURSOR, ItemStack.AIR)));
    }

    static Stream<Arguments> clicks() {
        return drops().map(drop -> Arguments.of(drop.get()[0], drop.get()[1]));
    }

    @ParameterizedTest(name = "{0}, in a container: {1}")
    @MethodSource("drops")
    public void aDropTakesItsItemsFromTheCursorOrTheSlot(Click click, boolean inContainer, ItemStack dropped,
                                                          ItemStack cursor, ItemStack slot, Env env) {
        final Player player = env.createPlayer(env.createFlatInstance(), new Pos(0, 42, 0));
        final List<ItemStack> drops = new ArrayList<>();
        env.process().eventHandler().addListener(ItemDropEvent.class, event -> drops.add(event.getItemStack()));
        final AbstractInventory clicked = prepare(player, inContainer);

        assertTrue(clicked.handleClick(player, click));

        assertEquals(List.of(dropped), drops);
        assertEquals(cursor, player.getInventory().getCursorItem());
        assertEquals(slot, clicked.getItemStack(CLICKED));
    }

    @ParameterizedTest(name = "{0}, in a container: {1}")
    @MethodSource("clicks")
    public void aCancelledDropLeavesTheCursorAndTheSlot(Click click, boolean inContainer, Env env) {
        final Player player = env.createPlayer(env.createFlatInstance(), new Pos(0, 42, 0));
        env.process().eventHandler().addListener(ItemDropEvent.class, event -> event.setCancelled(true));
        final AbstractInventory clicked = prepare(player, inContainer);

        assertFalse(clicked.handleClick(player, click));

        assertEquals(CURSOR, player.getInventory().getCursorItem());
        assertEquals(SLOT, clicked.getItemStack(CLICKED));
    }

    /** Fills the cursor and the slot to click, in an open container or in the player's own inventory. */
    private static AbstractInventory prepare(Player player, boolean inContainer) {
        final AbstractInventory clicked;
        if (inContainer) {
            final Inventory container = new Inventory(InventoryType.CHEST_2_ROW, Component.empty());
            player.openInventory(container);
            container.setItemStack(CLICKED, SLOT);
            clicked = container;
        } else {
            player.getInventory().setItemStack(CLICKED, SLOT);
            clicked = player.getInventory();
        }
        player.getInventory().setCursorItem(CURSOR);
        return clicked;
    }
}
