package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;

import java.util.regex.Pattern;

/**
 * Descriptor validation, the guard of the {@code [*] -> constructed} transition of
 * {@code docs/DESIGN-M2.md} §11.2 (REQ-M2-003).
 * <p>
 * A descriptor that does not pass stops the whole startup: the refusal is written to the log only,
 * nothing is sent to a client, and the process exits with a non zero code before it binds a port.
 */
final class PluginDescriptors {

    /**
     * The plugin id pattern, which is also the channel namespace pattern (§9).
     */
    static final String ID_PATTERN = "[a-z0-9][a-z0-9_-]{0,63}";

    /**
     * The namespace the host keeps for itself, so no plugin may claim it.
     */
    static final String RESERVED_ID = "synaxis";

    private static final Pattern ID = Pattern.compile(ID_PATTERN);

    private PluginDescriptors() {
    }

    /**
     * Checks one descriptor.
     *
     * @param descriptor the descriptor a plugin reported
     * @throws IllegalArgumentException if the id or the api version is refused
     */
    static void validate(PluginDescriptor descriptor) {
        final String id = descriptor.id();
        if (!ID.matcher(id).matches())
            throw new IllegalArgumentException(
                    "Refused plugin id \"" + id + "\": a plugin id must match " + ID_PATTERN);
        if (RESERVED_ID.equals(id))
            throw new IllegalArgumentException(
                    "Refused plugin id \"" + id + "\": that id is reserved for the host");
        if (descriptor.apiVersion() != PluginApi.API_VERSION)
            throw new IllegalArgumentException("Refused plugin id \"" + id + "\": it declares api_version="
                    + descriptor.apiVersion() + " but this host is api_version=" + PluginApi.API_VERSION);
    }
}
