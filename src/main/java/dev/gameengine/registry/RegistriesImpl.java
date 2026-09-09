package dev.gameengine.registry;

import net.kyori.adventure.nbt.BinaryTag;
import dev.gameengine.network.packet.server.SendablePacket;
import dev.gameengine.network.packet.server.common.TagsPacket;

import java.util.ArrayList;
import java.util.List;

final class RegistriesImpl {
    /**
     * The registries the server forwards to the client without interpreting them, see
     * {@link Registries#opaque(RegistryKey)}.
     */
    static final List<RegistryKey<Registry<BinaryTag>>> OPAQUE_KEYS = List.of(
            BuiltinRegistries.DIALOG,
            BuiltinRegistries.TRIM_MATERIAL,
            BuiltinRegistries.TRIM_PATTERN,
            BuiltinRegistries.BANNER_PATTERN,
            BuiltinRegistries.ENCHANTMENT,
            BuiltinRegistries.PAINTING_VARIANT,
            BuiltinRegistries.JUKEBOX_SONG,
            BuiltinRegistries.INSTRUMENT,
            BuiltinRegistries.WOLF_VARIANT,
            BuiltinRegistries.WOLF_SOUND_VARIANT,
            BuiltinRegistries.CAT_VARIANT,
            BuiltinRegistries.CAT_SOUND_VARIANT,
            BuiltinRegistries.CHICKEN_VARIANT,
            BuiltinRegistries.CHICKEN_SOUND_VARIANT,
            BuiltinRegistries.COW_VARIANT,
            BuiltinRegistries.COW_SOUND_VARIANT,
            BuiltinRegistries.FROG_VARIANT,
            BuiltinRegistries.PIG_VARIANT,
            BuiltinRegistries.PIG_SOUND_VARIANT,
            BuiltinRegistries.ZOMBIE_NAUTILUS_VARIANT,
            BuiltinRegistries.WORLD_CLOCK,
            BuiltinRegistries.TIMELINE,
            BuiltinRegistries.SULFUR_CUBE_ARCHETYPE);

    private RegistriesImpl() {
    }

    static List<SendablePacket> registryDataPackets(Registries registries, boolean excludeVanilla) {
        final List<SendablePacket> packets = new ArrayList<>();
        for (DynamicRegistry<?> registry : configurationRegistries(registries)) {
            packets.add(registry.registryDataPacket(registries, excludeVanilla));
        }
        return packets;
    }

    static TagsPacket tagsPacket(Registries registries) {
        final List<TagsPacket.Registry> entries = new ArrayList<>();
        for (Registry<?> registry : tagRegistries(registries)) {
            entries.add(registry.tagRegistry());
        }
        return new TagsPacket(entries);
    }

    /**
     * The registries sent during the configuration phase, in the order the client receives them.
     * The order is part of the protocol contract and must not change.
     */
    private static List<DynamicRegistry<?>> configurationRegistries(Registries registries) {
        return List.of(
                registries.chatType(),
                registries.biome(),
                registries.opaque(BuiltinRegistries.DIALOG),
                registries.damageType(),
                registries.opaque(BuiltinRegistries.TRIM_MATERIAL),
                registries.opaque(BuiltinRegistries.TRIM_PATTERN),
                registries.opaque(BuiltinRegistries.BANNER_PATTERN),
                registries.opaque(BuiltinRegistries.ENCHANTMENT),
                registries.opaque(BuiltinRegistries.PAINTING_VARIANT),
                registries.opaque(BuiltinRegistries.JUKEBOX_SONG),
                registries.opaque(BuiltinRegistries.INSTRUMENT),
                registries.opaque(BuiltinRegistries.WOLF_VARIANT),
                registries.opaque(BuiltinRegistries.WOLF_SOUND_VARIANT),
                registries.opaque(BuiltinRegistries.CAT_VARIANT),
                registries.opaque(BuiltinRegistries.CAT_SOUND_VARIANT),
                registries.opaque(BuiltinRegistries.CHICKEN_VARIANT),
                registries.opaque(BuiltinRegistries.CHICKEN_SOUND_VARIANT),
                registries.opaque(BuiltinRegistries.COW_VARIANT),
                registries.opaque(BuiltinRegistries.COW_SOUND_VARIANT),
                registries.opaque(BuiltinRegistries.FROG_VARIANT),
                registries.opaque(BuiltinRegistries.PIG_VARIANT),
                registries.opaque(BuiltinRegistries.PIG_SOUND_VARIANT),
                registries.opaque(BuiltinRegistries.ZOMBIE_NAUTILUS_VARIANT),
                registries.opaque(BuiltinRegistries.WORLD_CLOCK),
                registries.opaque(BuiltinRegistries.TIMELINE),
                registries.dimensionType(),
                registries.opaque(BuiltinRegistries.SULFUR_CUBE_ARCHETYPE));
    }

    private static List<Registry<?>> tagRegistries(Registries registries) {
        // These are the registries which contain tags used by the vanilla client.
        // Registries unused by the client do not need to be included.
        final List<Registry<?>> entries = new ArrayList<>();
        entries.add(registries.blocks());
        entries.add(registries.entityType());
        entries.add(registries.fluid());
        entries.add(registries.gameEvent());
        entries.add(registries.material());
        entries.addAll(configurationRegistries(registries));
        return entries;
    }
}
