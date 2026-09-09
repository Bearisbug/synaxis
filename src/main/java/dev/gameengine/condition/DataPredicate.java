package dev.gameengine.condition;

import net.kyori.adventure.nbt.BinaryTag;
import dev.gameengine.codec.Codec;

public interface DataPredicate {
    record Noop(BinaryTag content) implements DataPredicate {

    }

    // TODO
    Codec<DataPredicate> NBT_TYPE = Codec.NBT.transform(Noop::new, value -> ((Noop) value).content);
}
