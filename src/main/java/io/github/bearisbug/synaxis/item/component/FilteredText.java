package io.github.bearisbug.synaxis.item.component;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.adventure.ComponentCodecs;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import org.jetbrains.annotations.Nullable;

public record FilteredText<T>(T text, @Nullable T filtered) {

    public static final NetworkBuffer.Type<FilteredText<String>> STRING_NETWORK_TYPE = createNetworkType(NetworkBuffer.STRING);
    public static final Codec<FilteredText<String>> STRING_CODEC = createCodec(Codec.STRING);

    public static final NetworkBuffer.Type<FilteredText<Component>> COMPONENT_NETWORK_TYPE = createNetworkType(NetworkBuffer.COMPONENT);
    public static final Codec<FilteredText<Component>> COMPONENT_CODEC = createCodec(ComponentCodecs.COMPONENT);

    private static <T> NetworkBuffer.Type<FilteredText<T>> createNetworkType(NetworkBuffer.Type<T> inner) {
        return NetworkBufferTemplate.template(
                inner, FilteredText::text,
                inner.optional(), FilteredText::filtered,
                FilteredText::new);
    }

    private static <T> Codec<FilteredText<T>> createCodec(Codec<T> inner) {
        return StructCodec.struct(
                "raw", inner, FilteredText::text,
                "filtered", inner.optional(), FilteredText::filtered,
                FilteredText::new);
    }
}
