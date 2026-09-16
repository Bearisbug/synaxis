package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.plugin.AdmissionProvider;
import io.github.bearisbug.synaxis.plugin.PluginAdmission;

/**
 * Admission capability of one plugin (API-M2-011).
 *
 * @param pluginId the owning plugin
 * @param registry the one slot of the process
 */
record PluginAdmissionImpl(String pluginId, AdmissionRegistry registry) implements PluginAdmission {

    @Override
    public void register(AdmissionProvider provider) {
        registry.register(pluginId, provider);
    }
}
