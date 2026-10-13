package io.github.bearisbug.synaxis.game.rush;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.plugin.instance.EndCause;
import io.github.bearisbug.synaxis.plugin.instance.Exit;
import io.github.bearisbug.synaxis.plugin.instance.ManagedInstance;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * The host's managed instance as rush sees it ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-006}, §11.1)
 * over a real engine instance of the test's {@code Env}: the requests are recorded and refused from a
 * state they do not leave, a move really moves the player, and a close evacuates through the exits
 * without waiting for the moves, as the host does. Moves may be held for the test to finish one at a
 * time.
 */
final class FakeManaged implements ManagedInstance {

    final List<String> requests = new CopyOnWriteArrayList<>();
    final Map<Player, CompletableFuture<Boolean>> held = new ConcurrentHashMap<>();
    private final String id;
    private final InstanceContainer instance;
    volatile State state;
    volatile @Nullable EndCause cause;
    /** Moves into this instance wait for {@link #finish}. */
    volatile boolean holdMoves;

    FakeManaged(String id, InstanceContainer instance, State state) {
        this.id = id;
        this.instance = instance;
        this.state = state;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public UUID instanceId() {
        return instance.getUuid();
    }

    @Override
    public InstanceContainer instance() {
        return instance;
    }

    @Override
    public State state() {
        return state;
    }

    @Override
    public @Nullable EndCause endCause() {
        return cause;
    }

    @Override
    public Set<Player> players() {
        return Set.copyOf(instance.getPlayers());
    }

    @Override
    public CompletableFuture<Boolean> move(Player player, Pos at) {
        requests.add("move " + player.getUsername());
        if (state != State.OPEN && state != State.RUNNING) return CompletableFuture.completedFuture(false);
        if (!holdMoves) return CompletableFuture.completedFuture(arrive(player, at));
        final CompletableFuture<Boolean> move = new CompletableFuture<>();
        held.put(player, move);
        return move;
    }

    /**
     * Finishes a held move.
     *
     * @param player the player
     * @param moved  whether the player gets in
     */
    void finish(Player player, boolean moved) {
        final CompletableFuture<Boolean> move = Objects.requireNonNull(held.remove(player), "no move is held");
        move.complete(moved && arrive(player, Arena.SPAWN));
    }

    @Override
    public void start() {
        require(State.OPEN);
        requests.add("start");
        state = State.RUNNING;
    }

    @Override
    public void cancel(Function<Player, Exit> exit) {
        require(State.OPEN);
        requests.add("cancel");
        evacuate(exit);
        cause = EndCause.CANCELLED;
        state = State.CLOSED;
    }

    @Override
    public void end(EndCause cause) {
        require(State.RUNNING);
        requests.add("end " + cause);
        this.cause = cause;
        state = State.ENDING;
    }

    @Override
    public void settled(Function<Player, Exit> exit) {
        require(State.ENDING);
        requests.add("settled");
        evacuate(exit);
        state = State.CLOSED;
    }

    private boolean arrive(Player player, Pos at) {
        player.setInstance(instance, at).join();
        return true;
    }

    private void evacuate(Function<Player, Exit> exit) {
        for (final Player player : players()) {
            switch (exit.apply(player)) {
                case Exit.ToInstance to -> {
                    var _ = to.target().move(player, to.at());
                }
                case Exit.Kick kick -> player.kick(Component.translatable(kick.reasonKey()));
            }
        }
    }

    private void require(State expected) {
        if (state != expected) throw new IllegalStateException(id + " is " + state + ", not " + expected);
    }
}
