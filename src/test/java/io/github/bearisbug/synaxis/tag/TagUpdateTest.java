package io.github.bearisbug.synaxis.tag;

import io.github.bearisbug.synaxis.coordinate.Vec;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.bearisbug.synaxis.testing.TestUtils.assertEqualsSNBT;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class TagUpdateTest {

    @Test
    public void update() {
        var tag = Tag.Integer("coin");
        var handler = TagHandler.newHandler();
        handler.updateTag(tag, integer -> {
            assertNull(integer);
            return 5;
        });
        assertEquals(5, handler.getTag(tag));
        handler.updateTag(tag, integer -> {
            assertEquals(5, integer);
            return 10;
        });
        assertEquals(10, handler.getTag(tag));
    }

    @Test
    public void updateDefault() {
        var tag = Tag.Integer("coin").defaultValue(25);
        var handler = TagHandler.newHandler();
        handler.updateTag(tag, integer -> {
            assertEquals(25, integer);
            return 5;
        });
        assertEquals(5, handler.getTag(tag));
        handler.updateTag(tag, integer -> {
            assertEquals(5, integer);
            return 10;
        });
        assertEquals(10, handler.getTag(tag));
    }

    @Test
    public void updateRemoval() {
        var tag = Tag.Integer("coin");
        var handler = TagHandler.newHandler();
        handler.setTag(tag, 5);
        handler.updateTag(tag, integer -> {
            assertEquals(5, integer);
            return null;
        });
        assertNull(handler.getTag(tag));
        assertEqualsSNBT("{}", handler.asCompound());
    }

    @Test
    public void updateRemovalPath() {
        var tag = Tag.Integer("coin").path("path");
        var handler = TagHandler.newHandler();
        handler.setTag(tag, 5);
        handler.updateTag(tag, integer -> {
            assertEquals(5, integer);
            return null;
        });
        assertNull(handler.getTag(tag));
        assertEqualsSNBT("{}", handler.asCompound());
    }

    @Test
    public void updateAndGet() {
        var tag = Tag.Integer("coin");
        var handler = TagHandler.newHandler();
        var result = handler.updateAndGetTag(tag, integer -> {
            assertNull(integer);
            return 5;
        });
        assertEquals(5, result);
        result = handler.updateAndGetTag(tag, integer -> {
            assertEquals(5, integer);
            return 10;
        });
        assertEquals(10, result);
    }

    @Test
    public void getAndUpdate() {
        var tag = Tag.Integer("coin");
        var handler = TagHandler.newHandler();
        var result = handler.getAndUpdateTag(tag, integer -> {
            assertNull(integer);
            return 5;
        });
        assertNull(result);
        result = handler.getAndUpdateTag(tag, integer -> {
            assertEquals(5, integer);
            return 10;
        });
        assertEquals(5, result);
    }

    @Test
    public void updateHiddenSimilarity() {
        var tag1 = Tag.Integer("coin");
        var tag2 = Tag.Integer("coin").map(i -> i + 1, i -> i - 1);
        var handler = TagHandler.newHandler();
        handler.setTag(tag1, 5);
        assertDoesNotThrow(() -> handler.updateTag(tag2, _ -> 5));
        assertEquals(4, handler.getTag(tag1));
        assertEquals(5, handler.getTag(tag2));
    }

    @Test
    public void updateStructureConversion() {
        record Coin(int coin) {
        }

        var tag1 = Tag.Integer("coin").path("path");
        var tag2 = Tag.Structure("path", Coin.class);
        var handler = TagHandler.newHandler();
        handler.setTag(tag1, 5);
        assertEquals(5, handler.getTag(tag1));
        assertEquals(new Coin(5), handler.getTag(tag2));

        assertDoesNotThrow(() -> handler.updateTag(tag2, value -> new Coin(value.coin + 1)));
        assertEquals(6, handler.getTag(tag1));
        assertEquals(new Coin(6), handler.getTag(tag2));

        handler.updateTag(tag2, _ -> null);
        assertNull(handler.getTag(tag1));
        assertNull(handler.getTag(tag2));
    }

    @Test
    public void updateStructureConversionPath() {
        record Coin(int coin) {
        }

        var tag1 = Tag.Integer("coin").path("path", "path2");
        var tag2 = Tag.Structure("path2", Coin.class).path("path");
        var handler = TagHandler.newHandler();
        handler.setTag(tag1, 5);
        assertEquals(5, handler.getTag(tag1));
        assertEquals(new Coin(5), handler.getTag(tag2));

        assertDoesNotThrow(() -> handler.updateTag(tag2, value -> new Coin(value.coin + 1)));
        assertEquals(6, handler.getTag(tag1));
        assertEquals(new Coin(6), handler.getTag(tag2));

        handler.updateTag(tag2, _ -> null);
        assertNull(handler.getTag(tag1));
        assertNull(handler.getTag(tag2));
    }

    @Test
    public void updateStructureConversionPathDouble() {
        record Coin(int coin) {
        }
        record Structure(Coin test) {
        }

        var tag1 = Tag.Integer("coin").path("path", "test");
        var tag2 = Tag.Structure("path", Structure.class);

        var handler = TagHandler.newHandler();
        handler.setTag(tag1, 5);
        assertEquals(5, handler.getTag(tag1));
        assertEquals(new Structure(new Coin(5)), handler.getTag(tag2));

        assertDoesNotThrow(() -> handler.updateTag(tag2, value -> new Structure(new Coin(value.test.coin + 1))));
        assertEquals(6, handler.getTag(tag1));
        assertEquals(new Structure(new Coin(6)), handler.getTag(tag2));

        handler.updateTag(tag2, _ -> null);
        assertNull(handler.getTag(tag1));
        assertNull(handler.getTag(tag2));
    }

    @Test
    public void updateViewConversion() {
        record Coin(int coin) {
        }

        var tag1 = Tag.Integer("coin");
        var tag2 = Tag.View(Coin.class);
        var handler = TagHandler.newHandler();
        handler.setTag(tag1, 5);
        assertDoesNotThrow(() -> handler.updateTag(tag2, value -> new Coin(value.coin + 1)));
        assertEquals(6, handler.getTag(tag1));
        assertEquals(new Coin(6), handler.getTag(tag2));

        handler.updateTag(tag2, _ -> null);
        assertNull(handler.getTag(tag1));
        assertNull(handler.getTag(tag2));
    }

    /** An update reads the current value the way getTag does: a value of another type is no value, as for getTag. */
    @Test
    public void updateIncompatible() {
        var tagI = Tag.Integer("coin");
        var tagD = Tag.Double("coin");
        var handler = TagHandler.newHandler();
        handler.setTag(tagI, 5);
        handler.updateTag(tagD, value -> {
            assertNull(value);
            return 5d;
        });
        assertEquals(5d, handler.getTag(tagD));
    }

    /**
     * Loaded from NBT, an entry is kept under the tag of its NBT type (a byte, an int array), which shares no value
     * with a boolean or a UUID tag. The update used the stored value as if it did.
     */
    @Test
    public void updateAfterNbtRoundTrip() {
        var flag = Tag.Boolean("flag");
        var source = TagHandler.newHandler();
        source.setTag(flag, true);
        var handler = TagHandler.fromCompound(source.asCompound());

        handler.updateTag(flag, value -> !value);
        assertEquals(false, handler.getTag(flag));
    }

    @Test
    public void getAndSetAfterNbtRoundTrip() {
        var owner = Tag.UUID("owner");
        var first = UUID.fromString("5d2b6f1e-3c4a-4f7b-9e21-8a6c0d4b7f13");
        var second = UUID.fromString("9ab8ca63-3d7b-43ba-b805-a20a352dae9c");
        var source = TagHandler.newHandler();
        source.setTag(owner, first);
        var handler = TagHandler.fromCompound(source.asCompound());

        assertEquals(first, handler.getAndSetTag(owner, second));
        assertEquals(second, handler.getTag(owner));
    }

    /** A mapped tag shares no value with the tag it maps: the update has to hand over the mapped value. */
    @Test
    public void identityUpdateOfAMappedTagKeepsTheValue() {
        var raw = Tag.Double("speed");
        var perSecond = raw.map(perTick -> perTick * 20, value -> value / 20);
        var handler = TagHandler.newHandler();
        handler.setTag(raw, 0.5);
        assertEquals(10.0, handler.getTag(perSecond));

        handler.updateTag(perSecond, value -> value);
        assertEquals(0.5, handler.getTag(raw));
        assertEquals(10.0, handler.getTag(perSecond));
    }

    @Test
    public void updateInner() {
        var tag = Tag.Structure("vec", Vec.class);
        var tagX = Tag.Double("x").path("vec");
        var handler = TagHandler.newHandler();
        handler.setTag(tag, new Vec(5, 10, 15));
        handler.updateTag(tagX, x -> {
            assertEquals(5, x);
            return 7d;
        });
        assertEquals(7d, handler.getTag(tagX));
        assertEquals(new Vec(7, 10, 15), handler.getTag(tag));
    }
}
