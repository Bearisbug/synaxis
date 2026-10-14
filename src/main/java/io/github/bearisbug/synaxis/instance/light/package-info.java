/**
 * Sky and block light, by the rules of {@link io.github.bearisbug.synaxis.instance.light.LightBlocks}.
 * <p>
 * {@link io.github.bearisbug.synaxis.instance.light.LightEngine} lights a rectangle of chunks from a
 * {@link io.github.bearisbug.synaxis.instance.light.WorldView} into a
 * {@link io.github.bearisbug.synaxis.instance.light.LightResult}; the baker, {@code SynxRelight} and
 * {@code LightingChunk#relight} use it.
 * <p>
 * {@link io.github.bearisbug.synaxis.instance.light.Light} is the light a section stores and sends. Its
 * {@code calculateInternal}/{@code calculateExternal}, with
 * {@link io.github.bearisbug.synaxis.instance.light.LightCompute}, are the older section-at-a-time engine, which only
 * the lazy {@code LightingChunk#relightSection} still runs (docs/DESIGN-M3.md ADR-M3-003, §5); a change to how light
 * spreads goes into both engines. {@link io.github.bearisbug.synaxis.instance.light.LightCompute} also holds the
 * nibble constants both share.
 */
@NotNullByDefault
package io.github.bearisbug.synaxis.instance.light;

import org.jetbrains.annotations.NotNullByDefault;