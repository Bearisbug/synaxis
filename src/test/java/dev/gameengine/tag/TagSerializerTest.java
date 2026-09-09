package dev.gameengine.tag;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import dev.gameengine.codec.Transcoder;
import dev.gameengine.item.component.FireworkExplosion;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.gameengine.codec.CodecAssertions.assertOk;

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
}
