package io.github.bearisbug.synaxis.item.predicate;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;
import io.github.bearisbug.synaxis.instance.block.predicate.DataComponentPredicates;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.registry.RegistryTag;
import io.github.bearisbug.synaxis.utils.Range;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;

public record ItemPredicate(
        @Nullable RegistryTag<Material> items,
        @Nullable Range.Int count,
        @Nullable DataComponentPredicates predicates
) implements Predicate<ItemStack> {

    public static final Codec<ItemPredicate> CODEC = StructCodec.struct(
            "items", RegistryTag.codec(Registries::material).optional(), ItemPredicate::items,
            "count", Range.Int.CODEC.optional(), ItemPredicate::count,
            StructCodec.INLINE, DataComponentPredicates.CODEC.optional(), ItemPredicate::predicates,
            ItemPredicate::new
    );

    public static final NetworkBuffer.Type<ItemPredicate> NETWORK_TYPE = NetworkBuffer.TypedNBT(CODEC);

    public ItemPredicate(List<Material> items) {
        this(RegistryTag.direct(items.stream().map(Material::registryKey).toList()), null, null);
    }

    public ItemPredicate(Range.Int count, @Nullable List<Material> items) {
        this(items == null ? null : RegistryTag.direct(
                items.stream().map(Material::registryKey).toList()), count, null);
    }

    public ItemPredicate(DataComponentPredicates predicates) {
        this(null, null, predicates);
    }

    @Override
    public boolean test(ItemStack itemStack) {
        if (items != null && !items.contains(itemStack.material().registryKey()))
            return false;
        if (count != null && !count.inRange(itemStack.amount()))
            return false;

        return predicates == null || predicates.test(itemStack);
    }
}
