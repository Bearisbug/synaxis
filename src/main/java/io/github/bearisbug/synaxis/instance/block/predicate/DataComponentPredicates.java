package io.github.bearisbug.synaxis.instance.block.predicate;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;
import io.github.bearisbug.synaxis.component.DataComponent;
import io.github.bearisbug.synaxis.component.DataComponentMap;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * The component part of an item or block predicate: {@code exact} needs each of its component values to be equal
 * (the JSON {@code components}), {@code predicates} holds partial predicates by type (the JSON {@code predicates}).
 * Both have to pass; {@link DataComponentMap#EMPTY} and {@link ComponentPredicateSet#EMPTY} constrain nothing.
 *
 * @see DataComponentPredicate
 * @see ComponentPredicateSet
 */
public record DataComponentPredicates(DataComponentMap exact,
                                      ComponentPredicateSet predicates) implements Predicate<DataComponent.Holder> {

    public DataComponentPredicates {
        Objects.requireNonNull(exact, "Exact cannot be null. Use DataComponentMap.EMPTY to skip exact data component checks.");
        Objects.requireNonNull(predicates, "Component predicates cannot be null. Use ComponentPredicateSet.EMPTY to skip component predicate checks.");
    }

    public static final DataComponentPredicates EMPTY = new DataComponentPredicates(DataComponentMap.EMPTY, ComponentPredicateSet.EMPTY);

    public static final Codec<DataComponentPredicates> CODEC = StructCodec.struct(
            "components", DataComponent.MAP_NBT_TYPE.optional(DataComponentMap.EMPTY), DataComponentPredicates::exact,
            "predicates", ComponentPredicateSet.CODEC.optional(ComponentPredicateSet.EMPTY), DataComponentPredicates::predicates,
            DataComponentPredicates::new
    );

    public static final NetworkBuffer.Type<DataComponentPredicates> NETWORK_TYPE = NetworkBufferTemplate.template(
            DataComponent.MAP_NETWORK_TYPE, DataComponentPredicates::exact,
            ComponentPredicateSet.NETWORK_TYPE, DataComponentPredicates::predicates,
            DataComponentPredicates::new
    );

    @Override
    public boolean test(DataComponent.Holder holder) {
        for (DataComponent.Value entry : exact.entrySet()) {
            if (!Objects.equals(holder.get(entry.component()), entry.value())) {
                return false;
            }
        }
        return predicates.test(holder);
    }
}
