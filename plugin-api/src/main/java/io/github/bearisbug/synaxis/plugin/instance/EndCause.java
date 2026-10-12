package io.github.bearisbug.synaxis.plugin.instance;

/**
 * Why a managed instance ended or closed ({@code docs/DESIGN-PLAY.md} §9, the {@code cause} of the
 * ledger lines).
 */
public enum EndCause {

    /** A side reached the goal; given by the plugin. */
    OBJECTIVE,
    /** The time ran out; given by the plugin. */
    TIME_LIMIT,
    /** Every player left; given by the plugin. */
    EMPTY,
    /** The rules failed; given by the plugin. */
    ERROR,
    /** The plugin closed it before it started. */
    CANCELLED,
    /** Generating or lighting it threw, or took more than 30 s. */
    PREPARE_FAILED,
    /** The host tore the plugin down, which today only happens when its {@code onEnable} throws. */
    PLUGIN_DISABLED,
    /** The process stopped. */
    SHUTDOWN
}
