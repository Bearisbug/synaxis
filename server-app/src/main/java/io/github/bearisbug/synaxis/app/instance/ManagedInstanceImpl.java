package io.github.bearisbug.synaxis.app.instance;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.plugin.instance.EndCause;
import io.github.bearisbug.synaxis.plugin.instance.Exit;
import io.github.bearisbug.synaxis.plugin.instance.ManagedInstance;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * One managed instance and its state machine ({@code docs/DESIGN-PLAY.md} §11.1, {@code API-PLAY-006}).
 * <p>
 * Not implemented yet: every method throws {@link UnsupportedOperationException}.
 */
final class ManagedInstanceImpl implements ManagedInstance {

    private static UnsupportedOperationException unimplemented() {
        return new UnsupportedOperationException("the managed instances of API-PLAY-006 are not implemented yet");
    }

    @Override
    public String id() {
        throw unimplemented();
    }

    @Override
    public UUID instanceId() {
        throw unimplemented();
    }

    @Override
    public InstanceContainer instance() {
        throw unimplemented();
    }

    @Override
    public State state() {
        throw unimplemented();
    }

    @Override
    public @Nullable EndCause endCause() {
        throw unimplemented();
    }

    @Override
    public Set<Player> players() {
        throw unimplemented();
    }

    @Override
    public CompletableFuture<Boolean> move(Player player, Pos at) {
        throw unimplemented();
    }

    @Override
    public void start() {
        throw unimplemented();
    }

    @Override
    public void cancel(Function<Player, Exit> exit) {
        throw unimplemented();
    }

    @Override
    public void end(EndCause cause) {
        throw unimplemented();
    }

    @Override
    public void settled(Function<Player, Exit> exit) {
        throw unimplemented();
    }

    /**
     * The preparation, for a test to wait on.
     *
     * @return completes once every chunk is loaded and lit, exceptionally when that failed
     */
    CompletableFuture<Void> preparation() {
        return CompletableFuture.failedFuture(unimplemented());
    }

    /**
     * What the registration took on its thread, the second of the two figures of §15.
     *
     * @return nanoseconds from turning automatic loading off to the state event returning, -1 before
     */
    long registerNanos() {
        return -1;
    }
}
