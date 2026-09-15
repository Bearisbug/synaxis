package io.github.bearisbug.synaxis.game;


import net.kyori.adventure.key.Key;
import io.github.bearisbug.synaxis.registry.BuiltinRegistries;
import io.github.bearisbug.synaxis.registry.Registry;
import io.github.bearisbug.synaxis.registry.RegistryData;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import org.jetbrains.annotations.UnknownNullability;

/**
 * Represents a game event implementation.
 * Used for a wide variety of events, from weather to bed use to game mode to demo messages.
 */
@SuppressWarnings("removal")
record GameEventImpl(RegistryData.GameEventEntry registry, Key key, int id) implements GameEvent {
    static final Registry<GameEvent> REGISTRY = RegistryData.createStaticRegistry(
            BuiltinRegistries.GAME_EVENT, GameEventImpl::createImpl);

    /**
     * Creates a new {@link GameEventImpl} with the given namespace and properties.
     *
     * @param namespace  the namespace
     * @param properties the properties
     * @return a new {@link GameEventImpl}
     */
    private static GameEventImpl createImpl(String namespace, RegistryData.Properties properties) {
        return new GameEventImpl(RegistryData.gameEventEntry(namespace, properties));
    }

    /**
     * Creates a new {@link GameEventImpl} with the given registry.
     *
     * @param registry the registry
     */
    private GameEventImpl(RegistryData.GameEventEntry registry) {
        this(registry, registry.key(), registry.main().getInt("id"));
    }

    public static @UnknownNullability GameEvent get(RegistryKey<GameEvent> key) {
        return REGISTRY.get(key);
    }

}
