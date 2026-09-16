package io.github.bearisbug.synaxis.plugin;

/**
 * Identity a plugin reports to the host (API-M2-007).
 * <p>
 * M2 has no {@code depends} field: with a single compile time plugin there is nothing to resolve,
 * so declarative dependencies arrive with the second plugin (see {@code docs/DESIGN-M2.md} §28.3).
 *
 * @param id         the plugin id, matching {@code [a-z0-9][a-z0-9_-]{0,63}} and never {@code synaxis};
 *                   it is also the namespace of every channel this plugin owns
 * @param version    the self reported version, used for logs and {@code EVT:plugin_enabled} only
 * @param apiVersion the plugin API major this plugin was built against, see {@link PluginApi#API_VERSION}
 */
public record PluginDescriptor(String id, String version, int apiVersion) {
}
