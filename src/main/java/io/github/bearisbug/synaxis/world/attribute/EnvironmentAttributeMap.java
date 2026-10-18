package io.github.bearisbug.synaxis.world.attribute;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;
import io.github.bearisbug.synaxis.utils.Either;
import io.github.bearisbug.synaxis.world.attribute.EnvironmentAttribute.Modifier;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public record EnvironmentAttributeMap(Map<EnvironmentAttribute<?>, Entry<?, ?>> entries) {
    public static final EnvironmentAttributeMap EMPTY = new EnvironmentAttributeMap(Map.of());

    public static final Codec<EnvironmentAttributeMap> CODEC = EnvironmentAttribute.CODEC
            .mapValueTyped(Entry::codec0, true)
            .transform(EnvironmentAttributeMap::new, EnvironmentAttributeMap::entries);

    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(EnvironmentAttributeMap existing) {
        return new Builder(existing.entries);
    }

    /**
     * Creates a map of attribute entries. The map is encoded into the registry data of every configuration phase, so
     * an entry it could not encode is refused here instead.
     *
     * @param entries the entries by attribute
     * @throws NullPointerException     if an entry has a null argument
     * @throws IllegalArgumentException if an entry carries a modifier the type of its attribute does not take
     */
    public EnvironmentAttributeMap {
        entries = Map.copyOf(entries);
        entries.forEach(EnvironmentAttributeMap::check);
    }

    private static void check(EnvironmentAttribute<?> attribute, Entry<?, ?> entry) {
        Objects.requireNonNull(entry.argument(), () -> "Attribute " + attribute.key().asString() + " has a null argument");
        final EnvironmentAttributeTypeImpl<?> type = (EnvironmentAttributeTypeImpl<?>) attribute.type();
        if (!type.supports(entry.modifier()))
            throw new IllegalArgumentException("Attribute " + attribute.key().asString()
                    + " does not take this modifier; its type takes OVERRIDE and "
                    + type.operators().values().stream().sorted().toList());
    }

    public record Entry<T, Arg>(Arg argument, Modifier<T, Arg> modifier) {

        @SuppressWarnings("unchecked")
        public static <T> Codec<Entry<T, ?>> codec(EnvironmentAttribute<T> attribute) {
            // A value is represented by either a single value which acts as an override,
            // or a struct with `modifier` and `argument` keys (full codec).

            Codec<Entry<T, ?>> fullCodec = attribute.type().modifierCodec()
                    .unionType("modifier", Entry::fullCodec, Entry::modifier);

            final var override = new Modifier.Override<>(attribute.valueCodec());
            return Codec.Either(attribute.valueCodec(), fullCodec).transform(
                    either -> either.unify(
                            value -> new Entry<>(value, override),
                            u -> u),
                    entry -> entry.modifier instanceof Modifier.Override
                            ? Either.left((T) entry.argument) : Either.right(entry));
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static Codec<Entry<?, ?>> codec0(EnvironmentAttribute<?> attribute) {
            return (Codec) codec(attribute);
        }

        private static <T, Arg> StructCodec<Entry<T, Arg>> fullCodec(Modifier<T, Arg> modifier) {
            return StructCodec.struct(
                    "argument", modifier.argumentCodec(), Entry::argument,
                    (argument) -> new Entry<>(argument, modifier)
            );
        }

    }

    public static final class Builder {
        private final Map<EnvironmentAttribute<?>, Entry<?, ?>> entries = new HashMap<>();

        public Builder() {

        }

        public Builder(Map<EnvironmentAttribute<?>, Entry<?, ?>> existing) {
            entries.putAll(existing);
        }

        public <T> Builder set(EnvironmentAttribute<T> attribute, T value) {
            entries.put(attribute, new Entry<>(value, new Modifier.Override<>(attribute.valueCodec())));
            return this;
        }

        public <T, Arg> Builder modify(EnvironmentAttribute<T> attribute, Modifier<T, Arg> modifier, Arg argument) {
            entries.put(attribute, new Entry<>(argument, modifier));
            return this;
        }

        public EnvironmentAttributeMap build() {
            return new EnvironmentAttributeMap(entries);
        }
    }

}
