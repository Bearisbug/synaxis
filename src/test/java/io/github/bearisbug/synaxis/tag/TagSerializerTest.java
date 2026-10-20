package io.github.bearisbug.synaxis.tag;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import io.github.bearisbug.synaxis.codec.Transcoder;
import io.github.bearisbug.synaxis.item.component.FireworkExplosion;
import org.jetbrains.annotations.UnknownNullability;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.bearisbug.synaxis.codec.CodecAssertions.assertOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TagSerializerTest {
    @Test
    public void fromCompound(){
        var serializer = TagSerializer.fromCompound(
                c -> assertOk(FireworkExplosion.CODEC.decode(Transcoder.NBT, c)),
                explosion -> (CompoundBinaryTag) assertOk(FireworkExplosion.CODEC.encode(Transcoder.NBT, explosion)));
        var effect = new FireworkExplosion(FireworkExplosion.Shape.BURST, List.of(), List.of(), false, false);
        TagHandler handler = TagHandler.newHandler();
        serializer.write(handler, effect);
        Assertions.assertEquals(effect, serializer.read(handler));
    }

    /** The snapshot from readableCopy() is a reader the compound serializer accepts, like the handler itself. */
    @Test
    public void compoundReadsAReadableCopy() {
        TagHandler handler = TagHandler.newHandler();
        handler.setTag(Tag.Integer("a"), 1);
        handler.setTag(Tag.String("b").path("p"), "x");
        assertEquals(handler.asCompound(), TagSerializer.COMPOUND.read(handler.readableCopy()));
    }

    /** Any other reader is refused by name instead of failing a cast with no explanation. */
    @Test
    public void compoundRefusesAnotherReader() {
        final TagReadable reader = new TagReadable() {
            @Override
            public <T> @UnknownNullability T getTag(Tag<T> tag) {
                return tag.createDefault();
            }
        };
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TagSerializer.COMPOUND.read(reader));
        assertTrue(error.getMessage().contains(reader.getClass().getName()), error.getMessage());
    }
}
