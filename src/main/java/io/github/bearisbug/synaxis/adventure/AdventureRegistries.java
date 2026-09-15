package io.github.bearisbug.synaxis.adventure;

import io.github.bearisbug.synaxis.registry.Registries;
import org.jetbrains.annotations.ApiStatus;

/**
 * Registries used by the Adventure bridge where Adventure gives us no server context.
 *
 * <p>Both the {@link net.kyori.adventure.text.event.DataComponentValueConverterRegistry} SPI and
 * {@link net.kyori.adventure.text.serializer.ComponentSerializer} are resolved JVM wide, so the
 * bridge can only speak vanilla. Data components referring to entries a server registered itself
 * are out of its reach and have to be encoded through the registries of the owning process.</p>
 */
@ApiStatus.Internal
public final class AdventureRegistries {
    private static volatile Registries vanilla;

    public static Registries vanilla() {
        Registries registries = vanilla;
        if (registries == null) {
            synchronized (AdventureRegistries.class) {
                registries = vanilla;
                if (registries == null) vanilla = registries = Registries.vanilla();
            }
        }
        return registries;
    }

    private AdventureRegistries() {
    }
}
