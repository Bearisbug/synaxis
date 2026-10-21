package io.github.bearisbug.synaxis.gameevent;


import net.kyori.adventure.key.Key;
import io.github.bearisbug.synaxis.registry.BuiltinRegistries;
import io.github.bearisbug.synaxis.registry.Registry;
import io.github.bearisbug.synaxis.registry.RegistryData;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import org.jetbrains.annotations.UnknownNullability;

/**
 * An entry of the vanilla {@code game_event} registry, used by vibrations, sculk sensors and wardens; unrelated to
 * {@code ChangeGameStatePacket}.
 */
@SuppressWarnings("removal")
record GameEventImpl(RegistryData.GameEventEntry registry, Key key, int id) implements GameEvent {
    static final Registry<GameEvent> REGISTRY = RegistryData.createStaticRegistry(BuiltinRegistries.GAME_EVENT,
            (namespace, properties) -> {
                final RegistryData.GameEventEntry entry = RegistryData.gameEventEntry(namespace, properties);
                return new GameEventImpl(entry, entry.key(), entry.main().getInt("id"));
            });

    static @UnknownNullability GameEvent get(RegistryKey<GameEvent> key) {
        return REGISTRY.get(key);
    }

}
