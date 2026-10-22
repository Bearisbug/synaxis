package io.github.bearisbug.synaxis.app;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * The one rule of the ids a plugin names in its own namespace, {@code <plugin_id>:<name>}: its channels
 * ({@code docs/DESIGN-M2.md} {@code API-M2-008}) and its scene ids, action ids and panel buttons
 * ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-005}). A name comes with or without the plugin's own prefix,
 * which the host adds.
 */
public final class PluginIds {

    private static final Pattern NAME = Pattern.compile("[a-z0-9._/-]+");

    private PluginIds() {
    }

    /**
     * Resolves the full id a plugin means by {@code name}.
     *
     * @param pluginId the plugin
     * @param name     the name it gave, with or without its own prefix
     * @param maxBytes the most bytes of UTF-8 the full id may take
     * @param what     what the id is, with its article, for the message: {@code "a channel id"}, {@code "an id"}
     * @return {@code <plugin_id>:<name>}
     * @throws IllegalArgumentException if the name carries another plugin's namespace, its name part is not
     *                                  {@code [a-z0-9._/-]+}, or the full id is over {@code maxBytes} bytes
     */
    public static String qualify(String pluginId, String name, int maxBytes, String what) {
        final int colon = name.indexOf(':');
        final String path;
        if (colon < 0) {
            path = name;
        } else {
            final String namespace = name.substring(0, colon);
            if (!namespace.equals(pluginId))
                throw new IllegalArgumentException("\"" + name + "\" is not " + what + " of plugin_id=" + pluginId
                        + ": " + what + " is <plugin_id>:<name>, and " + pluginId + " does not own \"" + namespace + "\"");
            path = name.substring(colon + 1);
        }
        if (!NAME.matcher(path).matches())
            throw new IllegalArgumentException("\"" + name + "\" is not " + what + " of plugin_id=" + pluginId
                    + ": the name part must match " + NAME.pattern());
        final String id = pluginId + ":" + path;
        final int bytes = id.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxBytes)
            throw new IllegalArgumentException("\"" + id + "\" is " + bytes + " bytes, over the " + maxBytes + " bytes "
                    + what + " may take");
        return id;
    }
}
