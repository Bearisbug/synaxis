/**
 * Scenes, the HUD and actions of a plugin, carried by the host's channel {@code synaxis:play}
 * ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-005}).
 * <p>
 * The entry point is {@link io.github.bearisbug.synaxis.plugin.PluginContext#play()}. This package
 * holds four widget kinds, the vanilla camera and the locked cursor. Later versions add widget
 * kinds, cameras and cursor modes as new subtypes and constants of the sealed types and enums here,
 * which the binary compatibility gate accepts but which breaks an exhaustive {@code switch} without
 * a {@code default} branch at the next compile.
 */
@NotNullByDefault
package io.github.bearisbug.synaxis.plugin.play;

import org.jetbrains.annotations.NotNullByDefault;
