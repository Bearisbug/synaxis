package io.github.bearisbug.synaxis.app.scene;

import io.github.bearisbug.synaxis.game.protocol.play.PlayProtocol;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * The ids of {@code docs/DESIGN-PLAY.md} {@code API-PLAY-005}: a scene id, an action id and the action id of
 * a panel button are all {@code <plugin_id>:<name>}, under the same rule as the channel ids of
 * {@code PluginChannels}.
 */
final class PlayIds {

    private static final Pattern NAME = Pattern.compile("[a-z0-9._/-]+");

    private PlayIds() {
    }

    /**
     * Resolves the full id a plugin means by {@code name}.
     *
     * @param pluginId the plugin
     * @param name     the name it gave, with or without its own prefix
     * @return {@code <plugin_id>:<name>}
     * @throws IllegalArgumentException if the name carries another plugin's namespace, its name part is not
     *                                  {@code [a-z0-9._/-]+}, or the full id is over
     *                                  {@value PlayProtocol#MAX_ID_BYTES} bytes, the wire limit
     */
    static String qualify(String pluginId, String name) {
        final int colon = name.indexOf(':');
        final String path;
        if (colon < 0) {
            path = name;
        } else {
            final String namespace = name.substring(0, colon);
            if (!namespace.equals(pluginId))
                throw new IllegalArgumentException("\"" + name + "\" is not an id of plugin_id=" + pluginId
                        + ": an id is <plugin_id>:<name>, and " + pluginId + " does not own \"" + namespace + "\"");
            path = name.substring(colon + 1);
        }
        if (!NAME.matcher(path).matches())
            throw new IllegalArgumentException("\"" + name + "\" is not an id of plugin_id=" + pluginId
                    + ": the name part must match " + NAME.pattern());
        final String id = pluginId + ":" + path;
        final int bytes = id.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > PlayProtocol.MAX_ID_BYTES)
            throw new IllegalArgumentException("\"" + id + "\" is " + bytes + " bytes, over the "
                    + PlayProtocol.MAX_ID_BYTES + " an id may take on the wire");
        return id;
    }
}
