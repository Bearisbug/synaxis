package io.github.bearisbug.synaxis.item.component;

import net.kyori.adventure.nbt.BinaryTag;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.registry.BuiltinRegistries;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.registry.RegistryKey;

import java.util.HashMap;
import java.util.Map;

public record EnchantmentList(Map<RegistryKey<BinaryTag>, Integer> enchantments) {
    public static final EnchantmentList EMPTY = new EnchantmentList(Map.of());

    private static final Registries.Selector<BinaryTag> REGISTRY = registries -> registries.opaque(BuiltinRegistries.ENCHANTMENT);

    public static final NetworkBuffer.Type<EnchantmentList> NETWORK_TYPE = NetworkBufferTemplate.template(
            RegistryKey.<BinaryTag>networkType(REGISTRY).mapValue(NetworkBuffer.VAR_INT, Short.MAX_VALUE), EnchantmentList::enchantments,
            EnchantmentList::new);
    public static final Codec<EnchantmentList> CODEC = RegistryKey.<BinaryTag>codec(REGISTRY).mapValue(Codec.INT, Short.MAX_VALUE)
            .transform(EnchantmentList::new, EnchantmentList::enchantments);

    // 26.2 refuses a level outside 1..255 while it decodes the component, and drops the connection
    public EnchantmentList {
        enchantments = Map.copyOf(enchantments);
        for (Map.Entry<RegistryKey<BinaryTag>, Integer> entry : enchantments.entrySet()) {
            final int level = entry.getValue();
            if (level < 1 || level > 255)
                throw new IllegalArgumentException(
                        "Enchantment " + entry.getKey().key().asString() + " level " + level + " is outside 1..255");
        }
    }

    public EnchantmentList(RegistryKey<BinaryTag> enchantment, int level) {
        this(Map.of(enchantment, level));
    }

    public boolean has(RegistryKey<BinaryTag> enchantment) {
        return enchantments.containsKey(enchantment);
    }

    public int level(RegistryKey<BinaryTag> enchantment) {
        return enchantments.getOrDefault(enchantment, 0);
    }

    public EnchantmentList with(RegistryKey<BinaryTag> enchantment, int level) {
        Map<RegistryKey<BinaryTag>, Integer> newEnchantments = new HashMap<>(enchantments);
        newEnchantments.put(enchantment, level);
        return new EnchantmentList(newEnchantments);
    }

    public EnchantmentList remove(RegistryKey<BinaryTag> enchantment) {
        Map<RegistryKey<BinaryTag>, Integer> newEnchantments = new HashMap<>(enchantments);
        newEnchantments.remove(enchantment);
        return new EnchantmentList(newEnchantments);
    }
}
