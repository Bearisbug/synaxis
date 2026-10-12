package io.github.bearisbug.synaxis.game.rush;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.game.playkit.Countdown;
import io.github.bearisbug.synaxis.game.playkit.LocalQueue;
import io.github.bearisbug.synaxis.plugin.PluginAnalytics;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.instance.Exit;
import io.github.bearisbug.synaxis.plugin.instance.InstanceSpec;
import io.github.bearisbug.synaxis.plugin.instance.ManagedInstance;
import io.github.bearisbug.synaxis.plugin.instance.PluginInstances;
import io.github.bearisbug.synaxis.plugin.play.ActionResult;
import io.github.bearisbug.synaxis.plugin.play.Widget;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The lobby of rush ({@code docs/DESIGN-PLAY.md} §6.7.2, §6.7.3, the participant states of §11.3): the
 * queue, the countdown, the lobby scene and its widgets, and the round on demand.
 * <p>
 * {@link #tick()} runs in the serial part of every tick. {@link #arrived} runs on the tick thread of
 * the player or in the serial part, {@link #toggleQueue} and {@link #backToLobby} on the tick thread of
 * the player who sent the action, and {@link #left} wherever the disconnect is dispatched; what they
 * share with the tick is either concurrent or the lock of the queue.
 */
final class Lobby {

    private final PluginInstances instances;
    private final ManagedInstance lobby;
    private final LocalQueue<Player> queue;
    private final RushConfig config;
    private final RushHud hud;
    private final PluginAnalytics analytics;
    private final LongSupplier clock;
    private final Logger logger;
    private final Countdown countdown;

    /** The players in the lobby scene, who are sent the hint. */
    private final Map<UUID, Player> present = new ConcurrentHashMap<>();
    /** The players whose last arena failed to prepare, shown that until they press the queue key again. */
    private final Set<UUID> failed = ConcurrentHashMap.newKeySet();
    /** The queued players who were sent the countdown; serial part only. */
    private final Map<UUID, Player> counting = new HashMap<>();

    private @Nullable ManagedInstance preparing;
    private volatile @Nullable RushRound round;
    /** A countdown ran out while a round was still open: the next one waits for that round to close. */
    private boolean waitingForRound;

    Lobby(PluginInstances instances, ManagedInstance lobby, LocalQueue<Player> queue, RushConfig config, RushHud hud,
          PluginAnalytics analytics, LongSupplier clock, Logger logger) {
        this.instances = instances;
        this.lobby = lobby;
        this.queue = queue;
        this.config = config;
        this.hud = hud;
        this.analytics = analytics;
        this.clock = clock;
        this.logger = logger;
        this.countdown = new Countdown(clock);
    }

    /**
     * A player spawned into the lobby instance: after the admission, from the back button of a result,
     * or with everyone left in a settled round.
     *
     * @param player the player
     */
    void arrived(Player player) {
        present.put(player.getUuid(), player);
        hud.enter(player, RushHud.LOBBY);
        hud.show(player, hint(player, queue.size()));
    }

    /**
     * A player left the server.
     *
     * @param player the player
     */
    void left(Player player) {
        queue.leave(player);
        present.remove(player.getUuid());
        failed.remove(player.getUuid());
        hud.forget(player);
    }

    /**
     * The action {@code rush:queue}: joins the queue, or leaves it for a player in it.
     *
     * @param player the sender
     * @return {@code ok}
     */
    ActionResult toggleQueue(Player player) {
        failed.remove(player.getUuid());
        if (!queue.join(player)) queue.leave(player);
        return new ActionResult.Ok();
    }

    /**
     * The action {@code rush:back} of the result panel: sends the sender back to the lobby.
     *
     * @param player the sender
     * @return {@code ok}, also when there was nothing to do
     */
    ActionResult backToLobby(Player player) {
        final RushRound current = round;
        if (current != null) current.back(player);
        return new ActionResult.Ok();
    }

    /**
     * One tick of the lobby: the round, the arena being prepared or else the countdown, and the hint.
     */
    void tick() {
        final RushRound current = round;
        if (current != null) {
            current.tick();
            if (current.arena().state() == ManagedInstance.State.CLOSED) round = null;
        }
        final ManagedInstance arena = preparing;
        if (arena == null) count();
        else prepared(arena);
        final int queued = queue.size();
        for (final Player player : present.values()) hud.show(player, hint(player, queued));
    }

    private void count() {
        final List<Player> queued = queue.players();
        if (queued.size() < config.minPlayers()) {
            stopCountdown();
            return;
        }
        if (waitingForRound) {
            if (round != null) return;
            waitingForRound = false;
        }
        if (!countdown.running()) countdown.start(config.lobbyWait());
        if (countdown.done()) {
            stopCountdown();
            if (round != null) waitingForRound = true;
            else preparing = instances.create(arenaSpec());
            return;
        }
        final Set<UUID> ids = new HashSet<>();
        for (final Player player : queued) ids.add(player.getUuid());
        counting.entrySet().removeIf(entry -> {
            if (ids.contains(entry.getKey())) return false;
            hud.hide(entry.getValue(), RushHud.TIMER);
            return true;
        });
        for (final Player player : queued) {
            if (counting.putIfAbsent(player.getUuid(), player) == null)
                hud.showOnce(player, RushHud.timer("rush.timer.countdown", countdown.remaining(), true));
        }
    }

    private void stopCountdown() {
        countdown.cancel();
        for (final Player player : counting.values()) hud.hide(player, RushHud.TIMER);
        counting.clear();
    }

    private void prepared(ManagedInstance arena) {
        switch (arena.state()) {
            case PREPARING -> {
            }
            case OPEN -> {
                preparing = null;
                open(arena);
            }
            default -> {
                // Closed before it ever opened: the preparation failed, and everyone queued is out
                preparing = null;
                for (final Player player : queue.take(0, Integer.MAX_VALUE)) failed.add(player.getUuid());
            }
        }
    }

    private void open(ManagedInstance arena) {
        if (queue.size() < config.minPlayers()) {
            arena.cancel(this::exitOf);
            return;
        }
        final List<Player> players = queue.take(config.minPlayers(), RushConfig.MAX_PLAYERS);
        final List<CompletableFuture<Boolean>> moves = new ArrayList<>(players.size());
        for (final Player player : players) {
            present.remove(player.getUuid());
            counting.remove(player.getUuid());
            final CompletableFuture<Boolean> move = arena.move(player, Arena.SPAWN);
            // Right after the move: the host holds the scene back until the move is done (§6.4.3)
            hud.enter(player, RushHud.ROUND);
            moves.add(move);
            var _ = move.whenComplete((moved, _) -> {
                if (!Boolean.TRUE.equals(moved) && player.isOnline()) arrived(player);
            });
        }
        round = new RushRound(arena, config, lobby, moves, hud, analytics, clock, logger);
    }

    private InstanceSpec arenaSpec() {
        return new InstanceSpec("round", PluginApi.MATCH_DIMENSION_TYPE, Arena.ROUND_RADIUS, Arena.round(), Arena.SPAWN,
                config.resultLength().plusSeconds(15), this::exitOf);
    }

    /**
     * Where a player goes when an arena closes before its round: back to the lobby.
     *
     * @param player the player
     * @return the exit
     */
    Exit exitOf(Player player) {
        return new Exit.ToInstance(lobby, Arena.SPAWN);
    }

    private Widget hint(Player player, int queued) {
        return failed.contains(player.getUuid()) ? RushHud.hintFailed() : RushHud.hint(queued);
    }
}
