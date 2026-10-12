/**
 * The play kit ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-008}): helpers a game plugin builds its
 * rules from. A {@link io.github.bearisbug.synaxis.game.playkit.Round} drives a managed instance
 * through one round, a {@link io.github.bearisbug.synaxis.game.playkit.LocalQueue} holds the players
 * waiting for one, and a {@link io.github.bearisbug.synaxis.game.playkit.Countdown} times the wait.
 * <p>
 * The kit stands on the stable surface the way a plugin does, takes no ABI gate and makes no
 * compatibility promise: a class two games use is the candidate for {@code :plugin-api}.
 */
@NotNullByDefault
package io.github.bearisbug.synaxis.game.playkit;

import org.jetbrains.annotations.NotNullByDefault;
