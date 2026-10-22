package io.github.bearisbug.synaxis.plugin;

/**
 * Identity a plugin reports to the host (API-M2-007).
 * <p>
 * There is no {@code depends} field: declarative dependencies arrive with the descriptor file of
 * the plugin platform.
 *
 * @param id         the plugin id, matching {@code [a-z0-9][a-z0-9_-]{0,63}} and never {@code synaxis};
 *                   it is also the namespace of every channel this plugin owns. It is unique in the
 *                   process: the host refuses to start when two plugins declare the same id
 * @param version    the self reported version, used for logs and {@code EVT:plugin_enabled} only
 * @param apiVersion the plugin API major this plugin was built against, see {@link PluginApi#API_VERSION}
 */
public record PluginDescriptor(String id, String version, int apiVersion) {
}
