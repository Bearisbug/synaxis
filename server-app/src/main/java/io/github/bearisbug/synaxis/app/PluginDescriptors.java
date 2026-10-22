package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;
import org.jetbrains.annotations.Nullable;

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
     * Reads the id a plugin goes by, which the selection of {@code synaxis.app.plugins} needs of every discovered
     * plugin, the ones it leaves out too.
     *
     * @param plugin a discovered plugin
     * @return its id
     * @throws IllegalArgumentException if it reports no descriptor or a descriptor without an id; the message names
     *                                  the plugin class
     */
    static String id(SynaxisPlugin plugin) {
        final @Nullable PluginDescriptor descriptor = plugin.descriptor();
        if (descriptor == null)
            throw new IllegalArgumentException("Refused plugin_class=" + plugin.getClass().getName()
                    + ": its descriptor() returned null");
        final @Nullable String id = descriptor.id();
        if (id == null)
            throw new IllegalArgumentException("Refused plugin_class=" + plugin.getClass().getName()
                    + ": its descriptor has no id");
        return id;
    }

    /**
     * Checks the descriptor of a selected plugin.
     *
     * @param plugin the plugin
     * @throws IllegalArgumentException if it has no id or no version, or its id or its api version is refused; the
     *                                  message names the plugin class, since the id alone does not say which jar
     *                                  to look in
     */
    static void validate(SynaxisPlugin plugin) {
        final String id = id(plugin);
        final PluginDescriptor descriptor = plugin.descriptor();
        final String refused = "Refused plugin id \"" + id + "\" of plugin_class=" + plugin.getClass().getName() + ": ";
        if (!ID.matcher(id).matches())
            throw new IllegalArgumentException(refused + "a plugin id must match " + ID_PATTERN);
        if (RESERVED_ID.equals(id))
            throw new IllegalArgumentException(refused + "that id is reserved for the host");
        if (descriptor.version() == null)
            throw new IllegalArgumentException(refused + "its descriptor has no version");
        if (descriptor.apiVersion() != PluginApi.API_VERSION)
            throw new IllegalArgumentException(refused + "it declares api_version=" + descriptor.apiVersion()
                    + " but this host is api_version=" + PluginApi.API_VERSION);
    }
}
