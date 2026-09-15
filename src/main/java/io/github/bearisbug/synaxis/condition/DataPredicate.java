package io.github.bearisbug.synaxis.condition;

import net.kyori.adventure.nbt.BinaryTag;
import io.github.bearisbug.synaxis.codec.Codec;

public interface DataPredicate {
    record Noop(BinaryTag content) implements DataPredicate {

    }

    // TODO
    Codec<DataPredicate> NBT_TYPE = Codec.NBT.transform(Noop::new, value -> ((Noop) value).content);
}
