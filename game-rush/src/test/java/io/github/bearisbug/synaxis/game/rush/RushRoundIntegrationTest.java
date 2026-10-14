package io.github.bearisbug.synaxis.game.rush;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.entity.metadata.display.TextDisplayMeta;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.plugin.instance.InstanceSpec;
import io.github.bearisbug.synaxis.plugin.instance.ManagedInstance;
import io.github.bearisbug.synaxis.plugin.play.Widget;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules of one round of rush ({@code docs/DESIGN-PLAY.md} §6.7.2, §6.7.3, §19): real players of the
 * test's {@code Env} in a real arena, a stand-in for the host's managed instance and scene layer, and a
 * hand driven clock.
 */
@EnvTest
class RushRoundIntegrationTest {

    private static final Duration TICK = Duration.ofMillis(50);
    private static final Pos AT_A = new Pos(-11.5, Arena.GROUND, -11.5);

    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final FakePlay play = new FakePlay();
    private final RushHud hud = new RushHud(play);
    private final List<Map<String, ?>> ledger = new ArrayList<>();

    /** The arena and the lobby of one test, and the players already in the arena. */
    private record Setup(FakeManaged arena, FakeManaged lobby, List<Player> players) {
    }

    @Test
    void theTimerIsSetWhenTheRoundStartsWithTheWholeLengthAndNotBefore(Env env) {
        final Setup setup = setup(env, "alice", "bob");
        final RushRound round = round(setup, config(240, 150));
        for (final Player player : setup.players())
            assertEquals(List.of(), play.sets(player, RushHud.TIMER), "a timer was set before the round started");

        round.tick();

        assertEquals(ManagedInstance.State.RUNNING, setup.arena().state());
        for (final Player player : setup.players()) {
            assertEquals(List.of(RushHud.timer("rush.timer.round", Duration.ofMinutes(4), true)),
                    play.sets(player, RushHud.TIMER));
            assertEquals(RushHud.objective(150), play.widgets(player).get(RushHud.OBJECTIVE));
            assertEquals(2, ((Widget.Scoreboard) play.widgets(player).get(RushHud.SCORE)).rows().size());
        }
        // The labels are plain text, not translate keys (§6.7.5)
        assertEquals(List.of("A", "B", "C"), List.of(label(setup, 'A'), label(setup, 'B'), label(setup, 'C')));
    }

    @Test
    void aPlayerHoldingABeaconScoresEverySecondUntilTheTargetEndsTheRound(Env env) {
        final Setup setup = setup(env, "alice", "bob");
        final Player alice = setup.players().getFirst();
        alice.teleport(AT_A).join();
        final RushRound round = round(setup, config(240, 3));
        round.tick();

        run(round, Duration.ofSeconds(1));
        assertEquals(RushHud.capture('A', 10), play.widgets(alice).get(RushHud.CAPTURE));
        assertNull(play.widgets(setup.players().get(1)).get(RushHud.CAPTURE), "bob is in no circle");
        run(round, Duration.ofSeconds(9));
        assertEquals("A: alice", label(setup, 'A'), "the label does not name the owner");
        assertEquals(List.of("start"), setup.arena().requests);
        run(round, Duration.ofSeconds(2));

        assertEquals(List.of("start", "end OBJECTIVE"), setup.arena().requests);
        assertEquals(Map.of("round_id", "rush:round_1", "players", 2, "winner", "alice", "cause", "objective",
                "duration_ms", 12_000L), ledger.getFirst());
        final Widget.Panel panel = (Widget.Panel) play.widgets(alice).get(RushHud.RESULT);
        assertEquals(List.of(RushHud.resultRow(1, "alice", 1, 3, true), RushHud.resultRow(2, "bob", 0, 0, false)),
                panel.rows());
        assertEquals(RushHud.BACK, panel.buttons().getFirst().actionId());
        assertNull(play.widgets(alice).get(RushHud.CAPTURE), "the capture line outlived the round");
        assertEquals(RushHud.timer("rush.timer.round", Duration.ofSeconds(228), false), play.widgets(alice).get(RushHud.TIMER));
    }

    /** The CI run's shape (§6.7.6): nobody scores, the time runs out, and the two share the first rank. */
    @Test
    void aRoundEndsAtTheTimeLimitAndATieSharesTheRank(Env env) {
        final Setup setup = setup(env, "bob", "alice");
        final RushRound round = round(setup, config(5, 150));
        round.tick();
        run(round, Duration.ofSeconds(5).minus(TICK));
        assertEquals(List.of("start"), setup.arena().requests, "the round ended before its time was up");
        run(round, TICK);

        assertEquals(List.of("start", "end TIME_LIMIT"), setup.arena().requests);
        assertEquals("time_limit", ledger.getFirst().get("cause"));
        assertEquals("alice,bob", ledger.getFirst().get("winner"));
        final Player bob = setup.players().getFirst();
        assertEquals(List.of(RushHud.resultRow(1, "alice", 0, 0, false), RushHud.resultRow(1, "bob", 0, 0, true)),
                ((Widget.Panel) play.widgets(bob).get(RushHud.RESULT)).rows());
        assertEquals(RushHud.timer("rush.timer.round", Duration.ZERO, false), play.widgets(bob).get(RushHud.TIMER));
    }

    @Test
    void theHigherScoreRanksFirstThenTheMoreCapturesAndOtherwiseTheRankIsShared(Env env) {
        final Setup setup = setup(env, "carol", "dave", "alice", "bob");
        final List<RushRound.Standing> standings = new ArrayList<>();
        final int[][] scores = {{5, 2}, {3, 9}, {5, 2}, {5, 1}};
        for (int i = 0; i < scores.length; i++) {
            final RushRound.Standing standing = new RushRound.Standing(setup.players().get(i));
            standing.score = scores[i][0];
            standing.captures = scores[i][1];
            standings.add(standing);
        }

        final List<RushRound.Ranked> ranked = RushRound.rank(standings);

        assertEquals(List.of("1 alice", "1 carol", "3 bob", "4 dave"),
                ranked.stream().map(entry -> entry.rank() + " " + entry.standing().name()).toList());
    }

    @Test
    void aRoundNobodyIsLeftInEndsEmptyAndSettlesAtOnce(Env env) {
        final Setup setup = setup(env, "alice", "bob");
        final RushRound round = round(setup, config(240, 150));
        round.tick();
        for (final Player player : setup.players()) player.setInstance(setup.lobby().instance(), Arena.SPAWN).join();

        round.tick();
        assertEquals(List.of("start", "end EMPTY"), setup.arena().requests);
        assertEquals(Map.of("round_id", "rush:round_1", "players", 0, "winner", "", "cause", "empty", "duration_ms", 0L),
                ledger.getFirst());
        round.tick();

        assertEquals(List.of("start", "end EMPTY", "settled"), setup.arena().requests);
        assertEquals(ManagedInstance.State.CLOSED, setup.arena().state());
    }

    @Test
    void aParticipantWhoLeavesDropsOutOfTheScoresAndLetsGoOfTheBeacon(Env env) {
        final Setup setup = setup(env, "alice", "bob");
        final Player alice = setup.players().getFirst();
        final Player bob = setup.players().get(1);
        alice.teleport(AT_A).join();
        final RushRound round = round(setup, config(240, 150));
        round.tick();
        run(round, Duration.ofSeconds(11));
        assertEquals("A: alice", label(setup, 'A'));

        alice.setInstance(setup.lobby().instance(), Arena.SPAWN).join();
        run(round, Duration.ofSeconds(1));

        assertEquals("A", label(setup, 'A'), "the beacon of a player who left still names them");
        final Widget.Scoreboard scores = (Widget.Scoreboard) play.widgets(bob).get(RushHud.SCORE);
        assertEquals(List.of(new Widget.Row(Component.text("bob"), "0", true)), scores.rows());
        assertEquals(List.of("start"), setup.arena().requests, "one player left is still a round");
    }

    /** §19 "结算中只送按钮的人回大厅". */
    @Test
    void theBackButtonSendsOnlyThePresserOnceAndOnlyWhileTheResultShows(Env env) {
        final Setup setup = setup(env, "alice", "bob");
        final Player alice = setup.players().getFirst();
        final Player bob = setup.players().get(1);
        final RushRound round = round(setup, config(5, 150));
        round.tick();
        round.back(alice);
        assertEquals(List.of(), setup.lobby().requests, "the button worked while the round was running");
        run(round, Duration.ofSeconds(5));
        assertEquals(ManagedInstance.State.ENDING, setup.arena().state());

        round.back(alice);
        round.back(alice);
        round.tick();

        assertEquals(List.of("move alice"), setup.lobby().requests);
        assertEquals(setup.lobby().instance(), alice.getInstance());
        assertEquals(setup.arena().instance(), bob.getInstance(), "the button sent somebody else back too");
        assertEquals(ManagedInstance.State.ENDING, setup.arena().state());
        round.back(bob);
        round.tick();
        assertEquals(List.of("start", "end TIME_LIMIT", "settled"), setup.arena().requests,
                "the arena outlived its last player by more than one tick");
    }

    /**
     * Once the round is settled the arena is {@code CLOSED} and the host evacuates it, and until the move
     * of the evacuation lands the player is still inside: the back button does nothing then, the exit
     * alone sends the player back (§16).
     */
    @Test
    void theBackButtonDoesNothingWhileTheHostEvacuatesTheSettledArena(Env env) {
        final Setup setup = setup(env, "alice");
        final Player alice = setup.players().getFirst();
        final RushRound round = round(setup, config(5, 150));
        round.tick();
        run(round, Duration.ofSeconds(5));
        assertEquals(ManagedInstance.State.ENDING, setup.arena().state());
        setup.lobby().holdMoves = true;
        run(round, Duration.ofSeconds(10));
        assertEquals(ManagedInstance.State.CLOSED, setup.arena().state());
        assertEquals(List.of("move alice"), setup.lobby().requests, "the evacuation did not send alice back");
        assertEquals(setup.arena().instance(), alice.getInstance(), "the move of the evacuation was not held");

        round.back(alice);

        assertEquals(List.of("move alice"), setup.lobby().requests,
                "the button sent alice back while the evacuation was sending her");
        setup.lobby().finish(alice, true);
        assertEquals(setup.lobby().instance(), alice.getInstance());
    }

    @Test
    void theRoundWaitsForEveryMoveAndIsCancelledWhenNoneSucceeded(Env env) {
        final Setup setup = setup(env);
        final CompletableFuture<Boolean> first = new CompletableFuture<>();
        final CompletableFuture<Boolean> second = new CompletableFuture<>();
        final RushRound round = new RushRound(setup.arena(), config(240, 150), setup.lobby(), List.of(first, second),
                hud, this::record, clock::get, LoggerFactory.getLogger("plugin.rush"));

        first.complete(false);
        round.tick();
        assertEquals(List.of(), setup.arena().requests, "the round moved on before every move was done");
        second.complete(false);
        round.tick();
        round.tick();

        assertEquals(List.of("cancel"), setup.arena().requests);
        assertTrue(ledger.isEmpty(), "a round that never started wrote a result");
    }

    private static Setup setup(Env env, String... names) {
        final FakeInstances instances = new FakeInstances(env, ManagedInstance.State.OPEN);
        final FakeManaged arena = (FakeManaged) instances.create(new InstanceSpec("round", Arena.ROUND_RADIUS, Arena.round(), Arena.SPAWN));
        final FakeManaged lobby = (FakeManaged) new FakeInstances(env, ManagedInstance.State.RUNNING)
                .create(new InstanceSpec("lobby", Arena.LOBBY_RADIUS, Arena.lobby(), Arena.SPAWN));
        final List<Player> players = new ArrayList<>();
        for (final String name : names) {
            players.add(env.createConnection(new GameProfile(UUID.randomUUID(), name)).connect(arena.instance(), Arena.SPAWN));
        }
        return new Setup(arena, lobby, players);
    }

    private RushRound round(Setup setup, RushConfig config) {
        final List<CompletableFuture<Boolean>> moves = setup.players().stream()
                .map(_ -> CompletableFuture.completedFuture(true)).toList();
        return new RushRound(setup.arena(), config, setup.lobby(), moves, hud, this::record, clock::get,
                LoggerFactory.getLogger("plugin.rush"));
    }

    private static RushConfig config(int roundSeconds, int targetScore) {
        return new RushConfig(1, Duration.ofSeconds(3), Duration.ofSeconds(roundSeconds), Duration.ofSeconds(10), targetScore);
    }

    /** Ticks the round for this long, one tick of 50 ms at a time. */
    private void run(RushRound round, Duration duration) {
        for (long ticks = duration.dividedBy(TICK); ticks > 0; ticks--) {
            clock.addAndGet(TICK.toNanos());
            round.tick();
        }
    }

    private static String label(Setup setup, char letter) {
        for (final Entity entity : setup.arena().instance().getEntities()) {
            if (entity.getEntityType() != EntityType.TEXT_DISPLAY) continue;
            final Component text = ((TextDisplayMeta) entity.getEntityMeta()).getText();
            final String content = ((TextComponent) text).content();
            if (content.charAt(0) == letter) return content;
        }
        throw new AssertionError("no label for beacon " + letter);
    }

    /** The plugin's ledger: rush writes one event of §20 and only that one. */
    private void record(String event, Map<String, ?> attributes) {
        assertEquals("rush_round_ended", event, "rush wrote an event of §20 it does not own");
        ledger.add(attributes);
    }
}
