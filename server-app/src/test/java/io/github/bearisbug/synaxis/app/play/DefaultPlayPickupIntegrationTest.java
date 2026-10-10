package io.github.bearisbug.synaxis.app.play;

import io.github.bearisbug.synaxis.app.play.DefaultPlay.Feature;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.ItemEntity;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.event.item.PickupItemEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.inventory.PlayerInventory;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code PICKUP} of the default play layer ({@code docs/DESIGN-PROD.md} {@code API-PROD-031}): a player's pickup puts
 * the stack into the inventory, and one the inventory cannot take whole is cancelled, which leaves the item entity on
 * the ground for the engine to release ({@code LivingEntity#update}).
 */
@EnvTest
class DefaultPlayPickupIntegrationTest {

    private static final Pos SPAWN = new Pos(0.5, 40, 0.5);

    @Test
    void aPickupGoesIntoTheInventory(Env env) {
        final Instance instance = env.createFlatInstance();
        PlayFixture.attach(env, PlayFixture.hostNode(env), EnumSet.of(Feature.PICKUP), null);
        final Player player = env.createPlayer(instance, SPAWN);

        final PickupItemEvent pickup = pickUp(env, instance, player);

        assertFalse(pickup.isCancelled(), "the pickup of a player with room for the stack");
        assertEquals(ItemStack.of(Material.STONE), player.getInventory().getItemStack(0));
    }

    @Test
    void aPickupTheInventoryCannotTakeIsCancelled(Env env) {
        final Instance instance = env.createFlatInstance();
        PlayFixture.attach(env, PlayFixture.hostNode(env), EnumSet.of(Feature.PICKUP), null);
        final Player player = env.createPlayer(instance, SPAWN);
        fill(player.getInventory());

        final PickupItemEvent pickup = pickUp(env, instance, player);

        assertTrue(pickup.isCancelled(), "the pickup of a player whose 36 slots hold dirt");
        assertFilledWithDirt(player.getInventory());
    }

    /**
     * The inventory filled up between the host's decision and its taking the stack in, by a listener where a global
     * scope plugin listener runs: the apply stage finds no room and cancels the pickup itself.
     */
    @Test
    void aPickupThatNoLongerFitsWhenAppliedIsCancelled(Env env) {
        final Instance instance = env.createFlatInstance();
        final EventNode<Event> hostNode = PlayFixture.hostNode(env);
        PlayFixture.attach(env, hostNode, EnumSet.of(Feature.PICKUP), null);
        final Player player = env.createPlayer(instance, SPAWN);
        PlayFixture.pluginTier(hostNode, 0).addListener(PickupItemEvent.class, _ -> fill(player.getInventory()));

        final PickupItemEvent pickup = pickUp(env, instance, player);

        assertTrue(pickup.isCancelled(), "a pickup with no room left by the time it is applied");
        assertFilledWithDirt(player.getInventory());
    }

    /**
     * A pickup cancelled after the host decided on it, where a global scope plugin listener at {@code HIGHEST} runs,
     * puts nothing into the inventory: the item entity stays on the ground, so a stack taken in before that listener
     * ran would be a copy.
     */
    @Test
    void aPickupCancelledAfterTheHostLeavesTheInventoryAlone(Env env) {
        final Instance instance = env.createFlatInstance();
        final EventNode<Event> hostNode = PlayFixture.hostNode(env);
        PlayFixture.attach(env, hostNode, EnumSet.of(Feature.PICKUP), null);
        PlayFixture.pluginTier(hostNode, 2).addListener(PickupItemEvent.class, event -> event.setCancelled(true));
        final Player player = env.createPlayer(instance, SPAWN);

        pickUp(env, instance, player);

        assertTrue(Arrays.stream(player.getInventory().getItemStacks()).allMatch(ItemStack::isAir),
                "the inventory after a cancelled pickup holds " + Arrays.toString(player.getInventory().getItemStacks()));
    }

    /**
     * Dispatches the pickup of one stone lying at the player's feet, the way {@code LivingEntity#update} does.
     */
    private static PickupItemEvent pickUp(Env env, Instance instance, Player player) {
        final ItemEntity item = new ItemEntity(ItemStack.of(Material.STONE));
        item.setInstance(instance, SPAWN).join();
        final PickupItemEvent pickup = new PickupItemEvent(player, item);
        env.process().eventHandler().call(pickup);
        return pickup;
    }

    private static void fill(PlayerInventory inventory) {
        for (int slot = 0; slot < inventory.getInnerSize(); slot++)
            inventory.setItemStack(slot, ItemStack.of(Material.DIRT, 64));
    }

    private static void assertFilledWithDirt(PlayerInventory inventory) {
        for (int slot = 0; slot < inventory.getInnerSize(); slot++)
            assertEquals(ItemStack.of(Material.DIRT, 64), inventory.getItemStack(slot), "slot " + slot);
    }
}
