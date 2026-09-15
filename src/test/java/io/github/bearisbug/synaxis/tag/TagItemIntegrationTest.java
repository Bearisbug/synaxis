package io.github.bearisbug.synaxis.tag;

import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;

import static io.github.bearisbug.synaxis.testing.TestUtils.assertEqualsSNBT;
import static io.github.bearisbug.synaxis.testing.TestUtils.waitUntilCleared;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@EnvTest
public class TagItemIntegrationTest {

    @Test
    public void get(Env env) {
        var item = ItemStack.of(Material.DIAMOND);
        var tag = Tag.ItemStack("item", env.process());
        var handler = TagHandler.newHandler();
        handler.setTag(tag, item);

        assertEquals(item, handler.getTag(tag));
    }

    @Test
    public void getDifferentObject(Env env) {
        var item = ItemStack.of(Material.DIAMOND);
        var handler = TagHandler.newHandler();
        handler.setTag(Tag.ItemStack("item", env.process()), item);

        assertEquals(item, handler.getTag(Tag.ItemStack("item", env.process())));
    }

    @Test
    public void remove(Env env) {
        var item = ItemStack.of(Material.DIAMOND);
        var tag = Tag.ItemStack("item", env.process());
        var handler = TagHandler.newHandler();
        handler.setTag(tag, item);
        assertEquals(item, handler.getTag(tag));

        handler.setTag(tag, null);
        assertNull(handler.getTag(tag));
    }

    @Test
    public void gc(Env env) {
        var item = ItemStack.of(Material.DIAMOND);
        var tag = Tag.ItemStack("item", env.process());
        var handler = TagHandler.newHandler();
        handler.setTag(tag, item);
        assertEquals(item, handler.getTag(tag));
        handler.setTag(tag, null);

        var ref = new WeakReference<>(item);
        //noinspection UnusedAssignment
        item = null;
        waitUntilCleared(ref);
    }

    @Test
    public void invalidation(Env env) {
        var item = ItemStack.of(Material.DIAMOND);
        var item2 = ItemStack.of(Material.DIAMOND, 2);
        var handler = TagHandler.newHandler();

        var tag = Tag.ItemStack("item", env.process());
        handler.setTag(tag, item);
        assertEquals(item, handler.getTag(tag));
        handler.setTag(tag, item2);
        assertEquals(item2, handler.getTag(tag));
    }

    @Test
    @SuppressWarnings("deprecation") // deliberately keeps coverage of the deprecated API until its removal
    public void differentTagInvalidation(Env env) {
        var item = ItemStack.of(Material.DIAMOND);
        var item2 = ItemStack.of(Material.DIAMOND, 2);
        var handler = TagHandler.newHandler();

        var itemTag = Tag.ItemStack("item", env.process());
        var nbtTag = Tag.NBT("item");
        // Write the item using the ItemStack tag
        {
            handler.setTag(itemTag, item);
            assertEquals(item, handler.getTag(itemTag));
            assertEquals(item.toItemNBT(env.process()), handler.getTag(nbtTag));
        }
        // Override it with an NBT tag
        {
            handler.setTag(nbtTag, item2.toItemNBT(env.process()));
            assertEquals(item2, handler.getTag(itemTag));
            assertEquals(item2.toItemNBT(env.process()), handler.getTag(nbtTag));
        }
    }

    @Test
    public void snbt(Env env) {
        var handler = TagHandler.newHandler();
        var tag = Tag.ItemStack("item", env.process());
        handler.setTag(tag, ItemStack.of(Material.DIAMOND));
        assertEqualsSNBT("""
                {
                  "item": {
                    "id":"minecraft:diamond",
                    "count":1
                  }
                }
                """, handler.asCompound());
        handler.removeTag(tag);
        assertEqualsSNBT("{}", handler.asCompound());
    }
}
