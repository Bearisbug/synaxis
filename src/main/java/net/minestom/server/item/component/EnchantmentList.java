package net.minestom.server.item.component;

import net.kyori.adventure.nbt.BinaryTag;
import net.minestom.server.codec.Codec;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.NetworkBufferTemplate;
import net.minestom.server.registry.BuiltinRegistries;
import net.minestom.server.registry.Registries;
import net.minestom.server.registry.RegistryKey;

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

    public EnchantmentList {
        enchantments = Map.copyOf(enchantments);
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
