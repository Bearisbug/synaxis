package io.github.bearisbug.synaxis.game.playkit;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.plugin.instance.EndCause;
import io.github.bearisbug.synaxis.plugin.instance.Exit;
import io.github.bearisbug.synaxis.plugin.instance.ManagedInstance;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * One round of a game in a managed instance ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-008}); the
 * skeleton of RUN-077 group R, with the signatures and without the behaviour.
 */
public abstract class Round {

    private final ManagedInstance arena;

    protected Round(ManagedInstance arena, Duration countdown, Duration length, Duration result,
                    LongSupplier nanoClock, Logger logger) {
        this.arena = arena;
    }

    public final ManagedInstance arena() {
        return arena;
    }

    public final void tick() {
    }

    protected boolean readyToStart() {
        return true;
    }

    protected abstract void onCountdown(Duration left);

    protected abstract void onStart();

    protected abstract void onTick(Duration elapsed);

    protected abstract @Nullable EndCause decided();

    protected abstract void onResult(EndCause cause);

    protected abstract Exit exitOf(Player player);
}
