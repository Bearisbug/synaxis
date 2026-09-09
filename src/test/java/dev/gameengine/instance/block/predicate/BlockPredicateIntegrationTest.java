package dev.gameengine.instance.block.predicate;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import dev.gameengine.instance.block.Block;
import dev.gameengine.instance.block.SuspiciousGravelBlockHandler;
import dev.gameengine.item.ItemStack;
import dev.gameengine.item.Material;
import dev.gameengine.testing.Env;
import dev.gameengine.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class BlockPredicateIntegrationTest {
    @Test
    public void testEmptySource(Env env) {
        var itemNbt = ItemStack.of(Material.STONE).toItemNBT(env.process());
        var predicate = new BlockPredicate(CompoundBinaryTag.builder()
                .putString("LootTable", "minecraft:test")
                .put("item", itemNbt)
                .build());
        var block = Block.SUSPICIOUS_GRAVEL
                .withHandler(SuspiciousGravelBlockHandler.INSTANCE)
                .withNbt(CompoundBinaryTag.builder()
                .putString("LootTable", "minecraft:test")
                .put("item", itemNbt)
                .build());
        assertTrue(predicate.test(block));
    }

    @Test
    public void testNoMatchDeep(Env env) {
        var itemNbt1 = ItemStack.of(Material.STONE).toItemNBT(env.process());
        var itemNbt2 = ItemStack.of(Material.STONE).withAmount(2).toItemNBT(env.process());
        var predicate = new BlockPredicate(CompoundBinaryTag.builder()
                .putString("LootTable", "minecraft:test")
                .put("item", itemNbt1)
                .build());
        var block = Block.SUSPICIOUS_GRAVEL
                .withHandler(SuspiciousGravelBlockHandler.INSTANCE)
                .withNbt(CompoundBinaryTag.builder()
                .putString("LootTable", "minecraft:test")
                .put("item", itemNbt2)
                .build());
        assertFalse(predicate.test(block));
    }
}
