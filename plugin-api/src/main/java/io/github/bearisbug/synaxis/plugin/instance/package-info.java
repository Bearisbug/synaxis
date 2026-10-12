/**
 * Managed play instances: a lobby, a round or a dungeon whose lifecycle the host runs
 * ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-006}, the state machine of §11.1).
 * <p>
 * The entry point is {@link io.github.bearisbug.synaxis.plugin.PluginContext#instances()}. The host
 * prepares, registers, evacuates and unregisters the instances, writes a ledger line for every
 * transition and takes the instances of a plugin back when the plugin is torn down or the process
 * stops.
 */
@NotNullByDefault
package io.github.bearisbug.synaxis.plugin.instance;

import org.jetbrains.annotations.NotNullByDefault;
