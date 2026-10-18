package io.github.bearisbug.synaxis.world.attribute;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.KeyPattern;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.world.attribute.EnvironmentAttribute.Modifier;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

record EnvironmentAttributeTypeImpl<T>(
        Key key,
        Codec<T> codec,
        Codec<Modifier<T, ?>> modifierCodec,
        Map<Modifier<T, ?>, Modifier.Operator> operators
) implements EnvironmentAttribute.Type<T> {

    static <T> EnvironmentAttribute.Type<T> register(
            @KeyPattern String key,
            Codec<T> codec,
            Map<Modifier.Operator, Modifier<T, ?>> operators
    ) {
        final var withOverride = new HashMap<>(operators);
        withOverride.put(Modifier.Operator.OVERRIDE, new Modifier.Override<>(codec));

        final var inverse = new HashMap<Modifier<T, ?>, Modifier.Operator>(operators.size());
        for (var entry : operators.entrySet()) inverse.put(entry.getValue(), entry.getKey());

        final Codec<Modifier<T, ?>> modifierCodec = Modifier.Operator.CODEC.transform(op ->
                Objects.requireNonNull(withOverride.get(op), () -> "unsupported operator: " + op), inverse::get);
        return new EnvironmentAttributeTypeImpl<>(Key.key(key), codec, modifierCodec, Map.copyOf(inverse));
    }

    /** Whether an attribute of this type takes {@code modifier}: an override, or one of the operators of the type. */
    boolean supports(Modifier<?, ?> modifier) {
        return modifier instanceof Modifier.Override || operators.containsKey(modifier);
    }
}
