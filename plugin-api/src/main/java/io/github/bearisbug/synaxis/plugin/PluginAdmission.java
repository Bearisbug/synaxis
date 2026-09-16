package io.github.bearisbug.synaxis.plugin;

/**
 * The admission slot of the process (API-M2-011).
 * <p>
 * The engine requires a spawning instance while a connection is still in the configuration phase,
 * but only a plugin knows what matches exist. This is where that decision is handed back.
 */
public interface PluginAdmission {

    /**
     * Registers the admission provider of the whole process.
     *
     * @param provider the provider
     * @throws IllegalStateException if a provider is already registered; the whole process accepts
     *                               exactly one, and the host refuses to start without it
     */
    void register(AdmissionProvider provider);
}
