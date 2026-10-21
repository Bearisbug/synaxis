package io.github.bearisbug.synaxis.plugin;

/**
 * The plugin API version axis, see {@code docs/DESIGN-M2.md} §8.
 * <p>
 * This module is the surface a plugin is written against. It and {@code :protocol}, which it
 * carries along, are what the compatibility promise of {@code docs/DESIGN-M2.md} §8 covers, held to
 * binary compatibility by the {@code checkBinaryCompatibility} gate; the engine reached through
 * {@link PluginContext#process()} is not. The entry point is {@link SynaxisPlugin}; what the host
 * grants is {@link PluginContext}.
 */
public final class PluginApi {

    /**
     * The major version a plugin declares in its descriptor. A plugin whose value differs from this
     * one is refused; the number goes up by one on every breaking change of this module.
     */
    public static final int API_VERSION = 2;

    /**
     * The key of the one shallow dimension type the host registers before it binds, which a plugin
     * builds its match instances on (see {@code docs/DESIGN-M2.md} §11.3 and the startup sequence
     * of {@code API-M2-010}).
     * <p>
     * The dimension type registry is a dynamic one, sent once during the configuration phase: a
     * second one registered after the port is open would shift the dimension indices of everybody
     * already in game, so the host registers exactly one and every match shares it. Matches tell
     * themselves apart by their dimension name, which is free form and goes into no registry.
     * <p>
     * It has a minimum y of 0 and a height of 64, the world height of {@code docs/DESIGN-M2.md}
     * §9: blocks go at y 0 to 63. A plugin that has to be sure reads the height of the registered
     * dimension type at run time, as {@code game-rts} does before it builds a map.
     */
    public static final String MATCH_DIMENSION_TYPE = "synaxis:match";

    private PluginApi() {
    }
}
