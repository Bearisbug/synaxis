package io.github.bearisbug.synaxis.app.play;

import io.github.bearisbug.synaxis.app.play.DefaultPlay.Feature;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.GameMode;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerSpawnEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.inventory.PlayerInventory;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code STARTER_KIT} of the default play layer ({@code docs/DESIGN-PROD.md} {@code API-PROD-031}): a new character's
 * first spawn puts 64 stone in hotbar slot 0 and an iron pickaxe in slot 1 and leaves the game mode at survival; a
 * later spawn, or a character that is not new, gets nothing.
 */
@EnvTest
class DefaultPlayStarterKitIntegrationTest {

    private static final Pos SPAWN = new Pos(0.5, 40, 0.5);

    @Test
    void aNewCharacterGetsTheKitOnItsFirstSpawnOnly(Env env) {
        final Instance instance = env.createFlatInstance();
        final List<Player> asked = new CopyOnWriteArrayList<>();
        DefaultPlay.attach(env.process(), PlayFixture.hostNode(env), EnumSet.of(Feature.STARTER_KIT), character -> {
            asked.add(character);
            return true;
        }, null);

        final Player player = env.createPlayer(instance, SPAWN);

        assertEquals(List.of(player), asked, "asked whether the character is new");
        final PlayerInventory inventory = player.getInventory();
        assertEquals(ItemStack.of(Material.STONE, 64), inventory.getItemStack(0));
        assertEquals(ItemStack.of(Material.IRON_PICKAXE), inventory.getItemStack(1));
        for (int slot = 2; slot < PlayerInventory.INVENTORY_SIZE; slot++)
            assertEquals(ItemStack.AIR, inventory.getItemStack(slot), "slot " + slot);
        assertEquals(GameMode.SURVIVAL, player.getGameMode());

        // A spawn that is not the first, such as a respawn after a death
        inventory.clear();
        env.process().eventHandler().call(new PlayerSpawnEvent(player, instance, false));
        assertTrue(Arrays.stream(inventory.getItemStacks()).allMatch(ItemStack::isAir),
                "a later spawn gave " + Arrays.toString(inventory.getItemStacks()));
    }

    @Test
    void aCharacterThatIsNotNewGetsNothing(Env env) {
        final Instance instance = env.createFlatInstance();
        DefaultPlay.attach(env.process(), PlayFixture.hostNode(env), EnumSet.of(Feature.STARTER_KIT), _ -> false, null);

        final Player player = env.createPlayer(instance, SPAWN);

        assertTrue(Arrays.stream(player.getInventory().getItemStacks()).allMatch(ItemStack::isAir),
                "the first spawn of a character that is not new gave " + Arrays.toString(player.getInventory().getItemStacks()));
    }
}
