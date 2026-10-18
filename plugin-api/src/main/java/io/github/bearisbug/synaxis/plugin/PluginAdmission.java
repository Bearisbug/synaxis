package io.github.bearisbug.synaxis.plugin;

import org.jetbrains.annotations.ApiStatus;

/**
 * The admission slot of the process (API-M2-011).
 * <p>
 * The engine requires a spawning instance while a connection is still in the configuration phase,
 * but only a plugin knows what matches exist. This is where that decision is handed back.
 * <p>
 * Only the host implements this interface: a plugin is handed one and calls it.
 */
@ApiStatus.NonExtendable
public interface PluginAdmission {

    /**
     * Registers the admission provider of the whole process.
     *
     * @param provider the provider
     * @throws IllegalStateException if a provider is already registered; the whole process accepts
     *                               exactly one, and the host refuses to start without it
     * @throws NullPointerException  if the provider is null
     */
    void register(AdmissionProvider provider);
}
