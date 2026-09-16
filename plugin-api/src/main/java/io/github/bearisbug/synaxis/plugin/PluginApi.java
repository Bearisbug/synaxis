package io.github.bearisbug.synaxis.plugin;

/**
 * The plugin API version axis, see {@code docs/DESIGN-M2.md} §8.
 * <p>
 * This module is the only stable surface of the server: what it exports is held to binary
 * compatibility by the {@code checkBinaryCompatibility} gate, the engine below it is not. The
 * lifecycle and capability types ({@code SynaxisPlugin}, {@code PluginDescriptor},
 * {@code PluginContext}) land with REQ-M2-003 and REQ-M2-004.
 */
public final class PluginApi {

    /**
     * The major version a plugin declares in its descriptor. A plugin whose value differs from this
     * one is refused; the number goes up by one on every breaking change of this module.
     */
    public static final int API_VERSION = 1;

    /**
     * The key of the one shallow dimension type the host registers before it binds, which a plugin
     * builds its match instances on (see {@code docs/DESIGN-M2.md} §11.3 and the startup sequence
     * of {@code API-M2-010}).
     * <p>
     * The dimension type registry is a dynamic one, sent once during the configuration phase: a
     * second one registered after the port is open would shift the dimension indices of everybody
     * already in game, so the host registers exactly one and every match shares it. Matches tell
     * themselves apart by their dimension name, which is free form and goes into no registry.
     */
    public static final String MATCH_DIMENSION_TYPE = "synaxis:match";

    private PluginApi() {
    }
}
