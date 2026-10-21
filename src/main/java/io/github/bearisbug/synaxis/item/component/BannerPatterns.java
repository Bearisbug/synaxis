package io.github.bearisbug.synaxis.item.component;

import net.kyori.adventure.nbt.BinaryTag;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;
import io.github.bearisbug.synaxis.color.DyeColor;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.registry.BuiltinRegistries;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.registry.RegistryKey;

import java.util.ArrayList;
import java.util.List;

public record BannerPatterns(List<Layer> layers) {
    public static final int MAX_LAYERS = 1024;

    public static final NetworkBuffer.Type<BannerPatterns> NETWORK_TYPE = Layer.NETWORK_TYPE.list(MAX_LAYERS, Integer.MAX_VALUE).transform(BannerPatterns::new, BannerPatterns::layers);
    public static final Codec<BannerPatterns> CODEC = Layer.CODEC.list().transform(BannerPatterns::new, BannerPatterns::layers);

    public record Layer(RegistryKey<BinaryTag> pattern, DyeColor color) {
        private static final Registries.Selector<BinaryTag> REGISTRY = registries -> registries.opaque(BuiltinRegistries.BANNER_PATTERN);

        public static final NetworkBuffer.Type<Layer> NETWORK_TYPE = NetworkBufferTemplate.template(
                RegistryKey.holderNetworkType(REGISTRY), Layer::pattern,
                DyeColor.NETWORK_TYPE, Layer::color,
                Layer::new);
        public static final Codec<Layer> CODEC = StructCodec.struct(
                "pattern", RegistryKey.codec(REGISTRY), Layer::pattern,
                "color", DyeColor.CODEC, Layer::color,
                Layer::new);
    }

    public BannerPatterns {
        layers = List.copyOf(layers);
    }

    public BannerPatterns(Layer layer) {
        this(List.of(layer));
    }

    public BannerPatterns(RegistryKey<BinaryTag> pattern, DyeColor color) {
        this(new Layer(pattern, color));
    }

    public BannerPatterns with(Layer layer) {
        List<Layer> layers = new ArrayList<>(this.layers);
        layers.add(layer);
        return new BannerPatterns(layers);
    }
}
