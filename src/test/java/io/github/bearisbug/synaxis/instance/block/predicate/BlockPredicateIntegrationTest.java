package io.github.bearisbug.synaxis.instance.block.predicate;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.block.SuspiciousGravelBlockHandler;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
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
                .withHandler(new SuspiciousGravelBlockHandler(env.process(), true))
                .withNbt(CompoundBinaryTag.builder()
                .putString("LootTable", "minecraft:test")
                .put("item", itemNbt)
                .build());
        assertTrue(predicate.test(env.process(), block));
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
                .withHandler(new SuspiciousGravelBlockHandler(env.process(), true))
                .withNbt(CompoundBinaryTag.builder()
                .putString("LootTable", "minecraft:test")
                .put("item", itemNbt2)
                .build());
        assertFalse(predicate.test(env.process(), block));
    }
}
