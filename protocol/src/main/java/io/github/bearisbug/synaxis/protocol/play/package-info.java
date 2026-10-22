/**
 * The wire layer of {@code synaxis:play} ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-002},
 * {@code API-PLAY-003}): the messages, the widget catalogue and their limits as the host encodes them
 * and the client mod decodes them.
 * <p>
 * A plugin reaches this package through {@code :plugin-api} but writes its scenes, widgets and
 * actions with {@code io.github.bearisbug.synaxis.plugin.play}, which the host turns into the types
 * here. Several of them share a name there and do not mix: {@link Widget}, {@link PropValue.Row} and
 * {@link PropValue.Button} against {@code plugin.play.Widget}, {@code Widget.Row} and
 * {@code Widget.Button}, {@link C2SAction.Target} against {@code plugin.play.Target}; one of these
 * passed to a {@code Hud} fails to compile.
 */
package io.github.bearisbug.synaxis.protocol.play;
