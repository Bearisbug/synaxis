package io.github.bearisbug.synaxis.game.rush;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.game.playkit.Round;
import io.github.bearisbug.synaxis.plugin.PluginAnalytics;
import io.github.bearisbug.synaxis.plugin.instance.EndCause;
import io.github.bearisbug.synaxis.plugin.instance.Exit;
import io.github.bearisbug.synaxis.plugin.instance.ManagedInstance;
import io.github.bearisbug.synaxis.plugin.play.Widget;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * One round of rush ({@code docs/DESIGN-PLAY.md} §6.7.2, §6.7.3): who is in it, the beacons, the
 * scores, the end and the result.
 * <p>
 * Everything but {@link #back} runs in the serial part of the tick, from {@link Lobby#tick()}. The
 * participants are the players in the instance when it starts; one who leaves the instance drops out
 * of the scores, and a beacon they held goes back to nobody.
 */
final class RushRound extends Round {

    private static final int SCOREBOARD_ROWS = 8;
    private static final int PANEL_ROWS = 12;

    private final RushConfig config;
    private final ManagedInstance lobby;
    private final List<CompletableFuture<Boolean>> moves;
    private final RushHud hud;
    private final PluginAnalytics analytics;
    private final List<Beacon> beacons = Arena.beacons();
    private final Map<Beacon, Entity> labels = new HashMap<>();
    /** The owner each label shows, absent for nobody. */
    private final Map<Beacon, UUID> labelled = new HashMap<>();
    private final Map<UUID, Standing> standings = new LinkedHashMap<>();
    /** The players who pressed the back button; written on their tick threads. */
    private final Set<UUID> returning = ConcurrentHashMap.newKeySet();

    private Duration last = Duration.ZERO;
    private long scoredSeconds;
    private boolean cancelled;

    /**
     * Creates the round of an arena the lobby moves players into.
     *
     * @param arena     the arena, open
     * @param config    the configuration of rush
     * @param lobby     the lobby, where the players go back to
     * @param moves     the moves of the players taken from the queue into the arena
     * @param hud       the widgets of the players
     * @param analytics the ledger of {@code EVT:rush_round_ended}
     * @param clock     the monotonic clock, in nanoseconds
     * @param logger    the plugin's logger, for a callback that throws
     */
    RushRound(ManagedInstance arena, RushConfig config, ManagedInstance lobby, List<CompletableFuture<Boolean>> moves,
              RushHud hud, PluginAnalytics analytics, LongSupplier clock, Logger logger) {
        super(arena, Duration.ZERO, config.roundLength(), config.resultLength(), clock, logger);
        this.config = config;
        this.lobby = lobby;
        this.moves = List.copyOf(moves);
        this.hud = hud;
        this.analytics = analytics;
    }

    /**
     * The back button of the result panel, on the tick thread of the player who pressed it: sends that
     * player back to the lobby, once, while the round shows its result.
     *
     * @param player the player
     */
    void back(Player player) {
        if (arena().state() != ManagedInstance.State.ENDING || !arena().players().contains(player)
                || !returning.add(player.getUuid())) return;
        var _ = lobby.move(player, Arena.SPAWN);
    }

    /**
     * The participants in the order of the result: the higher score first, then the more captures; two
     * with both the same share a rank, and the next rank skips them (1, 1, 3).
     *
     * @param standings the participants
     * @return their ranks
     */
    static List<Ranked> rank(Collection<Standing> standings) {
        final List<Standing> sorted = new ArrayList<>(standings);
        sorted.sort(Comparator.comparingInt((Standing standing) -> -standing.score)
                .thenComparingInt(standing -> -standing.captures)
                .thenComparing(Standing::name));
        final List<Ranked> ranked = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            final Standing standing = sorted.get(i);
            final boolean tied = i > 0 && sorted.get(i - 1).score == standing.score
                    && sorted.get(i - 1).captures == standing.captures;
            ranked.add(new Ranked(tied ? ranked.get(i - 1).rank() : i + 1, standing));
        }
        return ranked;
    }

    /**
     * Ready once every move into the arena is done; when not one of them succeeded the arena is
     * cancelled instead.
     */
    @Override
    protected boolean readyToStart() {
        if (cancelled || !moves.stream().allMatch(CompletableFuture::isDone)) return false;
        if (moves.stream().noneMatch(RushRound::moved)) {
            cancelled = true;
            arena().cancel(this::exitOf);
            return false;
        }
        return true;
    }

    @Override
    protected void onCountdown(Duration left) {
        // The countdown is the lobby's: a round of rush opens with none
    }

    /**
     * The participants, the labels, and the round timer: set here and only here, with the whole length,
     * so that the moment the client receives it plus that length is when the round ends.
     */
    @Override
    protected void onStart() {
        for (final Player player : arena().players()) standings.put(player.getUuid(), new Standing(player));
        for (final Beacon beacon : beacons) labels.put(beacon, Arena.label(arena().instance(), beacon));
        for (final Standing standing : standings.values()) {
            hud.show(standing.player, RushHud.timer("rush.timer.round", config.roundLength(), true));
            hud.show(standing.player, RushHud.objective(config.targetScore()));
        }
        showScores();
    }

    @Override
    protected void onTick(Duration elapsed) {
        final long step = elapsed.minus(last).toNanos();
        last = elapsed;
        final Set<UUID> inArena = new HashSet<>();
        for (final Player player : arena().players()) inArena.add(player.getUuid());
        final Iterator<UUID> participants = standings.keySet().iterator();
        while (participants.hasNext()) {
            final UUID participant = participants.next();
            if (inArena.contains(participant)) continue;
            participants.remove();
            for (final Beacon beacon : beacons) beacon.forget(participant);
        }
        for (final Beacon beacon : beacons) {
            final Set<UUID> inside = new HashSet<>();
            for (final Standing standing : standings.values()) {
                if (beacon.contains(standing.player.getPosition())) inside.add(standing.player.getUuid());
            }
            final UUID captured = beacon.advance(inside, step);
            if (captured != null) standing(captured).captures++;
            relabel(beacon);
        }
        final long second = elapsed.toSeconds();
        if (second <= scoredSeconds) return;
        for (; scoredSeconds < second; scoredSeconds++) {
            for (final Beacon beacon : beacons) {
                final UUID owner = beacon.owner();
                if (owner != null) standing(owner).score++;
            }
        }
        showScores();
        showCaptures();
    }

    @Override
    protected @Nullable EndCause decided() {
        if (standings.isEmpty()) return EndCause.EMPTY;
        for (final Standing standing : standings.values()) {
            if (standing.score >= config.targetScore()) return EndCause.OBJECTIVE;
        }
        return null;
    }

    /**
     * Stops the timer, takes the capture line away, opens the result panel of each participant and
     * writes {@code EVT:rush_round_ended}.
     */
    @Override
    protected void onResult(EndCause cause) {
        final List<Ranked> ranking = rank(standings.values());
        final Duration left = config.roundLength().minus(last);
        final Widget stopped = RushHud.timer("rush.timer.round", left.isNegative() ? Duration.ZERO : left, false);
        for (final Standing standing : standings.values()) {
            hud.show(standing.player, stopped);
            hud.hide(standing.player, RushHud.CAPTURE);
            final List<Widget.Row> rows = new ArrayList<>();
            for (final Ranked ranked : ranking.subList(0, Math.min(PANEL_ROWS, ranking.size()))) {
                final Standing row = ranked.standing();
                rows.add(RushHud.resultRow(ranked.rank(), row.name(), row.captures, row.score, row == standing));
            }
            hud.show(standing.player, RushHud.result(rows));
        }
        final Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("round_id", arena().id());
        attributes.put("players", standings.size());
        attributes.put("winner", ranking.stream().filter(ranked -> ranked.rank() == 1).map(ranked -> ranked.standing().name())
                .sorted().collect(Collectors.joining(",")));
        attributes.put("cause", cause.name().toLowerCase(Locale.ROOT));
        attributes.put("duration_ms", last.toMillis());
        analytics.record("rush_round_ended", attributes);
    }

    @Override
    protected Exit exitOf(Player player) {
        return new Exit.ToInstance(lobby, Arena.SPAWN);
    }

    private static boolean moved(CompletableFuture<Boolean> move) {
        return move.state() == Future.State.SUCCESS && move.resultNow();
    }

    private Standing standing(UUID participant) {
        return Objects.requireNonNull(standings.get(participant), "not a participant of this round");
    }

    private void relabel(Beacon beacon) {
        final UUID owner = beacon.owner();
        if (Objects.equals(owner, labelled.get(beacon))) return;
        if (owner == null) labelled.remove(beacon);
        else labelled.put(beacon, owner);
        Arena.relabel(Objects.requireNonNull(labels.get(beacon)), beacon, owner == null ? null : standing(owner).name());
    }

    private void showScores() {
        final List<Ranked> ranking = rank(standings.values());
        for (final Standing viewer : standings.values()) {
            final List<Widget.Row> rows = new ArrayList<>();
            for (final Ranked ranked : ranking.subList(0, Math.min(SCOREBOARD_ROWS, ranking.size()))) {
                final Standing row = ranked.standing();
                rows.add(new Widget.Row(Component.text(row.name()), String.valueOf(row.score), row == viewer));
            }
            hud.show(viewer.player, RushHud.score(rows));
        }
    }

    private void showCaptures() {
        for (final Standing standing : standings.values()) {
            final Beacon beacon = circleOf(standing.player.getPosition());
            if (beacon == null) hud.hide(standing.player, RushHud.CAPTURE);
            else hud.show(standing.player, RushHud.capture(beacon.letter(), beacon.percent()));
        }
    }

    private @Nullable Beacon circleOf(Point position) {
        for (final Beacon beacon : beacons) {
            if (beacon.contains(position)) return beacon;
        }
        return null;
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
