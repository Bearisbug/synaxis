package io.github.bearisbug.synaxis.game.rush;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.game.playkit.Round;
import io.github.bearisbug.synaxis.plugin.PluginAnalytics;
import io.github.bearisbug.synaxis.plugin.instance.EndCause;
import io.github.bearisbug.synaxis.plugin.instance.Exit;
import io.github.bearisbug.synaxis.plugin.instance.ManagedInstance;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * One round of rush ({@code docs/DESIGN-PLAY.md} §6.7.3); the skeleton of RUN-077 group R.
 */
final class RushRound extends Round {

    RushRound(ManagedInstance arena, RushConfig config, ManagedInstance lobby, List<CompletableFuture<Boolean>> moves,
              RushHud hud, PluginAnalytics analytics, LongSupplier clock, Logger logger) {
        super(arena, Duration.ZERO, config.roundLength(), config.resultLength(), clock, logger);
    }

    void back(Player player) {
    }

    static List<Ranked> rank(Collection<Standing> standings) {
        return List.of();
    }

    @Override
    protected void onCountdown(Duration left) {
    }

    @Override
    protected void onStart() {
    }

    @Override
    protected void onTick(Duration elapsed) {
    }

    @Override
    protected @Nullable EndCause decided() {
        return null;
    }

    @Override
    protected void onResult(EndCause cause) {
    }

    @Override
    protected Exit exitOf(Player player) {
        return new Exit.Kick("synaxis:match-unavailable");
    }

    /**
     * One participant of the round.
     */
    static final class Standing {

        final Player player;
        int score;
        int captures;

        Standing(Player player) {
            this.player = player;
        }

        String name() {
            return player.getUsername();
        }
    }

    /**
     * A participant and its rank.
     *
     * @param rank     the rank, 1 for the first
     * @param standing the participant
     */
    record Ranked(int rank, Standing standing) {
    }
}
