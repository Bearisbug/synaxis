package io.github.bearisbug.synaxis.gameevent;


import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.KeyPattern;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.registry.Registry;
import io.github.bearisbug.synaxis.registry.RegistryData;
import io.github.bearisbug.synaxis.registry.StaticProtocolObject;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Objects;

/**
 * An entry of the vanilla {@code game_event} registry, used by vibrations, sculk sensors and wardens; unrelated to
 * {@code ChangeGameStatePacket}.
 */
public sealed interface GameEvent extends StaticProtocolObject<GameEvent>, GameEvents permits GameEventImpl {

    NetworkBuffer.Type<GameEvent> NETWORK_TYPE = NetworkBuffer.VAR_INT.transform(
            id -> Objects.requireNonNull(GameEvent.fromId(id), () -> "unknown game event id: " + id), GameEvent::id);

    /**
     * Returns the game event registry.
     *
     * @return the game event registry
     * @deprecated registry data will no longer be exposed. {@code notificationRadius} has no other accessor yet:
     * an {@code int notificationRadius()} comes before this method is removed.
     */
    @Deprecated(forRemoval = true)
    @SuppressWarnings("removal")
    @Override
    @Contract(pure = true)
    RegistryData.GameEventEntry registry();

    static Collection<GameEvent> values() {
        return GameEventImpl.REGISTRY.values();
    }

    static @Nullable GameEvent fromKey(@KeyPattern String key) {
        try {
            return fromKey(Key.key(key));
        } catch (InvalidKeyException _) {
            return null;
        }
    }

    static @Nullable GameEvent fromKey(Key key) {
        return GameEventImpl.REGISTRY.get(key);
    }

    static @Nullable GameEvent fromId(int id) {
        return GameEventImpl.REGISTRY.get(id);
    }

    static Registry<GameEvent> staticRegistry() {
        return GameEventImpl.REGISTRY;
    }

}
