package io.github.bearisbug.synaxis.registry;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.KeyPattern;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.Result;
import io.github.bearisbug.synaxis.codec.StructCodec;
import io.github.bearisbug.synaxis.codec.Transcoder;
import io.github.bearisbug.synaxis.codec.Transcoder.MapBuilder;
import io.github.bearisbug.synaxis.codec.Transcoder.MapLike;
import io.github.bearisbug.synaxis.registry.RegistryTagImpl.Backed;
import io.github.bearisbug.synaxis.registry.RegistryTagImpl.Direct;
import io.github.bearisbug.synaxis.registry.RegistryTagImpl.Empty;
import org.intellij.lang.annotations.Subst;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * The codecs that need a registry to resolve what they are looking at.
 */
public final class RegistryCodecs {
    /**
     * Shortcut for {@link RegistryCodecs#RegistryTaggedUnion(String, Registry, Function)}
     * where {@code key} will be {@code "type"}
     *
     * @param registry         the codec registry
     * @param serializerGetter the codec getter
     * @param <T>              the struct codec type.
     * @return a {@link StructCodec}
     */
    @Contract(pure = true, value = "_, _ -> new")
    public static <T> StructCodec<T> RegistryTaggedUnion(
            Registry<StructCodec<? extends T>> registry,
            Function<T, StructCodec<? extends T>> serializerGetter
    ) {
        return RegistryCodecs.RegistryTaggedUnion("type", registry, serializerGetter);
    }

    /**
     * Shortcut for {@link RegistryCodecs#RegistryTaggedUnion(String, Registries.Selector, Function)}
     * where {@code selector} will be {@code registry}
     *
     * @param key              the map key
     * @param registry         the codec registry
     * @param serializerGetter the codec getter
     * @param <T>              the struct codec type.
     * @return a {@link StructCodec}
     */
    @Contract(pure = true, value = "_, _, _ -> new")
    public static <T> StructCodec<T> RegistryTaggedUnion(
            String key,
            Registry<StructCodec<? extends T>> registry,
            Function<T, StructCodec<? extends T>> serializerGetter
    ) {
        Objects.requireNonNull(registry, "registry");
        // A fixed registry is a selector that ignores the registries it is given
        return RegistryCodecs.RegistryTaggedUnion(key, (_) -> registry, serializerGetter);
    }

    /**
     * Shortcut for {@link RegistryCodecs#RegistryTaggedUnion(String, Registries.Selector, Function)}
     * where {@code key} will be {@code "type"}
     *
     * @param registrySelector the registry selector used during lookup.
     * @param serializerGetter the serializer for each value of {@link T}
     * @param <T>              the codec type
     * @return a {@link StructCodec} bidirectionally mapping values of {@link T}
     */
    @Contract(pure = true, value = "_, _ -> new")
    public static <T> StructCodec<T> RegistryTaggedUnion(
            Registries.Selector<StructCodec<? extends T>> registrySelector,
            Function<T, StructCodec<? extends T>> serializerGetter
    ) {
        return new RegistryTaggedUnionImpl<>("type", registrySelector, serializerGetter);
    }

    /**
     * Creates a {@link StructCodec} to bidirectionally map values of {@link T} to their encoded values
     * <br>
     * Registry selectors will be used to lookup values of codecs of {@link T}.
     * Then will be used to map to object {@link T} from {@code key}
     *
     * @param key              the map key for {@link T}
     * @param registrySelector the registry selector used during lookup.
     * @param serializerGetter the serializer for each value of {@link T}
     * @param <T>              the codec type
     * @return a {@link StructCodec} bidirectionally mapping values of {@link T}
     */
    @Contract(pure = true, value = "_, _, _ -> new")
    public static <T> StructCodec<T> RegistryTaggedUnion(
            String key,
            Registries.Selector<StructCodec<? extends T>> registrySelector,
            Function<T, StructCodec<? extends T>> serializerGetter
    ) {
        return new RegistryTaggedUnionImpl<>(key, registrySelector, serializerGetter);
    }


    record RegistryKeyImpl<T>(Registries.Selector<T> selector) implements Codec<RegistryKey<T>> {
        RegistryKeyImpl {
            Objects.requireNonNull(selector, "selector");
        }

        @Override
        public <D> Result<RegistryKey<T>> decode(Transcoder<D> coder, D value) {
            if (!(coder instanceof RegistryTranscoder<D> context))
                return new Result.Error<>("Missing registries in transcoder");
            final var registry = selector.select(context.registries());
            final Result<String> referenceResult = coder.getString(value);
            if (!(referenceResult instanceof Result.Ok(@Subst("a")String reference)))
                return referenceResult.cast();
            final RegistryKey<T> key = registry.getKey(Key.key(reference));
            if (key == null) return new Result.Error<>("Unknown key " + reference + " for registry " + registry.key());
            return new Result.Ok<>(key);
        }

        @Override
        public <D> Result<D> encode(Transcoder<D> coder, @Nullable RegistryKey<T> value) {
            if (value == null) return new Result.Error<>("null");
            if (!(coder instanceof RegistryTranscoder<D>))
                return new Result.Error<>("Missing registries in transcoder");
            return new Result.Ok<>(coder.createString(value.key().asString()));
        }
    }

    record RegistryTagImpl<T>(Registries.Selector<T> selector) implements Codec<RegistryTag<T>> {
        // Per vanilla, this codec supports registryless context, in which case it can only decode direct tags.
        // That is the only case in which the entries of a direct tag are not checked against the registry.
        RegistryTagImpl {
            Objects.requireNonNull(selector, "selector");
        }

        @Override
        public <D> Result<RegistryTag<T>> decode(Transcoder<D> coder, D value) {
            final var context = coder instanceof RegistryTranscoder<D> transcoder ? transcoder : null;
            final var registry = context != null ? selector.select(context.registries()) : null;
            final Result<String> tagKeyResult = coder.getString(value);
            if (tagKeyResult instanceof Result.Ok(String tagKeyStr)) {
                if (registry != null && tagKeyStr.startsWith("#")) {
                    final var tagKey = TagKey.<T>ofHash(tagKeyStr);
                    final var tag = registry.getTag(tagKey);
                    return tag != null ? new Result.Ok<>(tag)
                            : new Result.Error<>("Unknown tag " + tagKey + " for registry " + registry.key());
                }
                if (registry == null) return new Result.Ok<>(RegistryTag.direct(RegistryKey.unsafeOf(tagKeyStr)));
                // A tag of one entry is written as that entry alone, which has to be registered like any list entry
                final RegistryKey<T> single = registry.getKey(Key.key(tagKeyStr));
                if (single == null) return new Result.Error<>("Unknown key " + tagKeyStr + " for registry " + registry.key());
                return new Result.Ok<>(RegistryTag.direct(single));
            }
            final Result<List<D>> entriesResult = coder.getList(value);
            if (entriesResult instanceof Result.Ok(List<D> entries)) {
                // Order matters: RegistryTagImpl.Direct#equals is order sensitive so that a tag decoded here
                // compares equal to the same tag read from the network, which preserves the declared order
                final Set<RegistryKey<T>> keys = new LinkedHashSet<>(entries.size());
                for (D entry : entries) {
                    final Result<String> keyResult = coder.getString(entry);
                    if (!(keyResult instanceof Result.Ok(@Subst("a")String key)))
                        return keyResult.mapError(e -> "Invalid tag entry: " + e).cast();
                    final RegistryKey<T> registryKey = registry != null ? registry.getKey(Key.key(key)) : RegistryKey.unsafeOf(key);
                    if (registryKey == null)
                        return new Result.Error<>("Unknown key " + key + " for registry " + registry.key());
                    keys.add(registryKey);
                }
                return new Result.Ok<>(RegistryTag.direct(keys));
            }

            return new Result.Error<>("Invalid tag value: " + value);
        }

        @Override
        public <D> Result<D> encode(Transcoder<D> coder, @Nullable RegistryTag<T> value) {
            if (value == null) return new Result.Error<>("null");
            return switch (value) {
                case Backed<T> backed ->
                        new Result.Ok<>(coder.createString(backed.key().hashedKey()));
                case Empty() -> new Result.Ok<>(coder.emptyList());
                case Direct(var entries) -> {
                    if (entries.isEmpty()) yield new Result.Ok<>(coder.emptyList());
                    if (entries.size() == 1)
                        yield new Result.Ok<>(coder.createString(entries.getFirst().key().asString()));
                    final Transcoder.ListBuilder<D> result = coder.createList(entries.size());
                    for (final RegistryKey<T> key : entries)
                        result.add(coder.createString(key.key().asString()));
                    yield new Result.Ok<>(result.build());
                }
            };
        }
    }

    @SuppressWarnings("unchecked")
    record RegistryTaggedUnionImpl<T>(
            String key,
            Registries.Selector<StructCodec<? extends T>> registrySelector,
            Function<T, StructCodec<? extends T>> valueToCodec
    ) implements StructCodec<T> {
        RegistryTaggedUnionImpl {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(registrySelector, "registrySelector");
            Objects.requireNonNull(valueToCodec, "valueToCodec");
        }

        @Override
        public <D> Result<T> decodeFromMap(Transcoder<D> coder, MapLike<D> map) {
            if (!(coder instanceof RegistryTranscoder<D> context))
                return new Result.Error<>("Missing registries in transcoder");
            final var registry = registrySelector.select(context.registries());

            final Result<String> type = map.getValue(key).map(coder::getString);
            if (!(type instanceof Result.Ok(@KeyPattern String tag)))
                return type.mapError(e -> key + ": " + e).cast();
            final StructCodec<T> innerCodec = (StructCodec<T>) registry.get(Key.key(tag));
            if (innerCodec == null) return new Result.Error<>("No such key: " + tag);

            return innerCodec.decodeFromMap(coder, map);
        }

        @Override
        public <D> Result<D> encodeToMap(Transcoder<D> coder, T value, MapBuilder<D> map) {
            if (!(coder instanceof RegistryTranscoder<D> context))
                return new Result.Error<>("Missing registries in transcoder");
            final var registry = registrySelector.select(context.registries());

            final StructCodec<T> innerCodec = (StructCodec<T>) valueToCodec.apply(value);
            final RegistryKey<StructCodec<? extends T>> type = registry.getKey(innerCodec);
            if (type == null) return new Result.Error<>("Unregistered serializer for: " + value);

            map.put(key, coder.createString(type.key().asString()));
            return innerCodec.encodeToMap(coder, value, map);
        }
    }
}
