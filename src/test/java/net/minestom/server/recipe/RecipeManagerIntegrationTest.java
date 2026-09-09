package net.minestom.server.recipe;

import net.minestom.server.item.Material;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.packet.server.CachedPacket;
import net.minestom.server.network.packet.server.play.DeclareRecipesPacket;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The DeclareRecipesPacket is cached but nothing ever invalidated it, so every recipe registered after the first
 * player logged in stayed invisible to all later players.
 */
@EnvTest
public class RecipeManagerIntegrationTest {

    private record PropertyRecipe(Material material) implements Recipe {
        @Override
        public Map<RecipeProperty, List<Material>> itemProperties() {
            return Map.of(RecipeProperty.FURNACE_INPUT, List.of(material));
        }
    }

    @Test
    public void addingARecipeInvalidatesTheCachedPacket(Env env) {
        final RecipeManager manager = env.process().recipe();
        final CachedPacket cached = (CachedPacket) manager.getDeclareRecipesPacket();

        final var before = (DeclareRecipesPacket) cached.packet(ConnectionState.PLAY);
        assertEquals(0, itemPropertyCount(before));

        final Recipe recipe = new PropertyRecipe(Material.STONE);
        manager.addRecipe(recipe);

        final var afterAdd = (DeclareRecipesPacket) cached.packet(ConnectionState.PLAY);
        assertTrue(itemPropertyCount(afterAdd) > 0, "a new recipe must reach the cached packet");

        manager.removeRecipe(recipe);

        final var afterRemove = (DeclareRecipesPacket) cached.packet(ConnectionState.PLAY);
        assertEquals(0, itemPropertyCount(afterRemove), "a removed recipe must leave the cached packet");
    }

    private static int itemPropertyCount(DeclareRecipesPacket packet) {
        return packet.itemProperties().size();
    }
}
