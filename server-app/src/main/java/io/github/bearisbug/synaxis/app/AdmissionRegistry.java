package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.plugin.AdmissionProvider;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * The one admission provider slot of the process (API-M2-011).
 * <p>
 * Exactly one provider may exist: the host has a single {@code setSpawningInstance} call point, and
 * two plugins both claiming to decide where a player goes have no defined resolution. The host
 * refuses to bind a port while the slot is empty.
 */
final class AdmissionRegistry {

    private record Registration(String pluginId, AdmissionProvider provider) {
    }

    private volatile @Nullable Registration registration;

    /**
     * @throws NullPointerException if the provider is null, which would hold the slot and turn every
     *                              connection away
     */
    void register(String pluginId, AdmissionProvider provider) {
        Objects.requireNonNull(provider, "provider");
        final Registration current = this.registration;
        if (current != null)
            throw new IllegalStateException("The process accepts one admission provider: plugin_id=" + pluginId
                    + " tried to register a second one, plugin_id=" + current.pluginId() + " holds the slot");
        this.registration = new Registration(pluginId, provider);
    }

    /**
     * Drops the provider if it belongs to the given plugin, part of the one shot teardown of
     * REQ-M2-004. Once the slot is empty every new connection is turned away.
     *
     * @param pluginId the plugin being disabled
     */
    void unregister(String pluginId) {
        final Registration current = this.registration;
        if (current != null && current.pluginId().equals(pluginId)) this.registration = null;
    }

    boolean isRegistered() {
        return this.registration != null;
    }

    @Nullable String pluginId() {
        final Registration current = this.registration;
        return current == null ? null : current.pluginId();
    }

    @Nullable AdmissionProvider provider() {
        final Registration current = this.registration;
        return current == null ? null : current.provider();
    }
}
