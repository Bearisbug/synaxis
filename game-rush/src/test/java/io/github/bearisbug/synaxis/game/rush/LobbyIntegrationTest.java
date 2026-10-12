package io.github.bearisbug.synaxis.game.rush;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.game.playkit.LocalQueue;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.instance.EndCause;
import io.github.bearisbug.synaxis.plugin.instance.Exit;
import io.github.bearisbug.synaxis.plugin.instance.InstanceSpec;
import io.github.bearisbug.synaxis.plugin.instance.ManagedInstance;
import io.github.bearisbug.synaxis.plugin.play.ActionResult;
import io.github.bearisbug.synaxis.plugin.play.Widget;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lobby of rush ({@code docs/DESIGN-PLAY.md} §6.7.2, §6.7.3, the participant states of §11.3, §19):
 * real players of the test's {@code Env} in a real lobby, a stand-in for the host's managed instances and
 * scene layer, and a hand driven clock. The lobby is ticked by hand, as the plugin's task does in the
 * serial part of every tick.
 */
@EnvTest
class LobbyIntegrationTest {

    private static final Duration TICK = Duration.ofMillis(50);

    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final FakePlay play = new FakePlay();
    private final LocalQueue<Player> queue = new LocalQueue<>();
    private final List<Map<String, ?>> ledger = new ArrayList<>();

    /** One lobby under test with its arenas. */
    private record Setup(Lobby rules, FakeManaged lobby, FakeInstances arenas, Env env) {

        Player join(String name) {
            final Player player = env.createConnection(new GameProfile(UUID.randomUUID(), name))
                    .connect(lobby.instance(), Arena.SPAWN);
            rules.arrived(player);
            return player;
        }
    }

    @Test
    void aPlayerArrivingEntersTheLobbySceneWithTheHint(Env env) {
        final Setup setup = setup(env, config(2, 10, 240));

        final Player alice = setup.join("alice");

        assertEquals(RushHud.LOBBY, play.scene(alice));
        assertEquals(Map.of(RushHud.HINT, RushHud.hint(0)), play.widgets(alice));
    }

    @Test
    void theQueueKeyTogglesAndTheHintFollowsTheCount(Env env) {
        final Setup setup = setup(env, config(2, 10, 240));
        final Player alice = setup.join("alice");
        final Player bob = setup.join("bob");

        assertInstanceOf(ActionResult.Ok.class, setup.rules().toggleQueue(alice));
        tick(setup);
        assertEquals(RushHud.hint(1), play.widgets(bob).get(RushHud.HINT));
        assertEquals(RushHud.hint(1), play.widgets(alice).get(RushHud.HINT));
        final int sets = play.sets(bob, RushHud.HINT).size();
        tick(setup);
        assertEquals(sets, play.sets(bob, RushHud.HINT).size(), "an unchanged hint was sent again");

        setup.rules().toggleQueue(alice);
        tick(setup);

        assertEquals(RushHud.hint(0), play.widgets(bob).get(RushHud.HINT));
        assertEquals(0, queue.size());
    }

    @Test
    void theCountdownReachesTheQueuedOnlyAndStopsBelowTheMinimum(Env env) {
        final Setup setup = setup(env, config(2, 10, 240));
        final Player alice = setup.join("alice");
        final Player bob = setup.join("bob");
        final Player carol = setup.join("carol");
        setup.rules().toggleQueue(alice);
        tick(setup);
        assertNull(play.widgets(alice).get(RushHud.TIMER), "one of two queued started the countdown");

        setup.rules().toggleQueue(bob);
        tick(setup);
        assertEquals(RushHud.timer("rush.timer.countdown", Duration.ofSeconds(10), true), play.widgets(alice).get(RushHud.TIMER));
        assertEquals(RushHud.timer("rush.timer.countdown", Duration.ofSeconds(10), true), play.widgets(bob).get(RushHud.TIMER));
        assertNull(play.widgets(carol).get(RushHud.TIMER), "the countdown reached a player who is not queued");
        run(setup, Duration.ofSeconds(4));
        setup.rules().toggleQueue(carol);
        tick(setup);
        assertEquals(RushHud.timer("rush.timer.countdown", Duration.ofMillis(5_950), true), play.widgets(carol).get(RushHud.TIMER),
                "a player joining the countdown got another time left than the others");
        assertEquals(1, play.sets(alice, RushHud.TIMER).size(), "a running countdown was sent again");

        setup.rules().toggleQueue(carol);
        setup.rules().toggleQueue(bob);
        tick(setup);

        for (final Player player : List.of(alice, bob, carol)) assertNull(play.widgets(player).get(RushHud.TIMER));
        assertTrue(setup.arenas().specs.isEmpty());
    }

    @Test
    void aCountdownRunningOutCreatesTheArena(Env env) {
        final Setup setup = setup(env, config(2, 10, 240));
        final Player alice = setup.join("alice");
        final Player bob = setup.join("bob");
        setup.rules().toggleQueue(alice);
        setup.rules().toggleQueue(bob);
        tick(setup);
        run(setup, Duration.ofSeconds(10).minus(TICK));
        assertTrue(setup.arenas().specs.isEmpty(), "the arena was created before the countdown ran out");

        tick(setup);

        assertEquals(1, setup.arenas().specs.size());
        final InstanceSpec spec = setup.arenas().specs.getFirst();
        assertEquals("round", spec.name());
        assertEquals(PluginApi.MATCH_DIMENSION_TYPE, spec.dimensionType());
        assertEquals(Arena.ROUND_RADIUS, spec.chunkRadius());
        assertEquals(Arena.SPAWN, spec.spawn());
        assertEquals(Duration.ofSeconds(25), spec.settleTimeout(), "the settle timeout is not the result time plus 15 s");
        assertEquals(new Exit.ToInstance(setup.lobby(), Arena.SPAWN), spec.fallbackExit().apply(alice));
        assertNull(play.widgets(alice).get(RushHud.TIMER), "the countdown stayed on screen while the arena was prepared");
        assertEquals(2, queue.size(), "the players left the queue before the arena opened");
        final Instance arena = setup.arenas().last().instance();
        assertEquals(Block.BEACON, arena.getBlock(-12, Arena.GROUND, -12));
        assertEquals(Block.BEACON, arena.getBlock(12, Arena.GROUND, -12));
        assertEquals(Block.BEACON, arena.getBlock(0, Arena.GROUND, 14));
        assertEquals(Block.STONE, arena.getBlock(0, Arena.GROUND - 1, 0));
        assertEquals(Block.STONE, arena.getBlock(-25, Arena.GROUND - 4, 24));
        assertEquals(Block.AIR, arena.getBlock(0, Arena.GROUND - 5, 0));
        assertEquals(Block.AIR, arena.getBlock(0, Arena.GROUND, 0));
        assertEquals(Block.BARRIER, arena.getBlock(-25, Arena.GROUND, 0));
        assertEquals(Block.BARRIER, arena.getBlock(24, Arena.GROUND + 3, 7));
        assertEquals(Block.BARRIER, arena.getBlock(3, Arena.GROUND + 1, -25));
        assertEquals(Block.AIR, arena.getBlock(-24, Arena.GROUND, 0), "the wall is thicker than one block");
        assertEquals(Block.AIR, arena.getBlock(24, Arena.GROUND + 4, 0), "the wall is higher than four blocks");
        assertEquals(Block.AIR, arena.getBlock(25, Arena.GROUND - 1, 0), "the floor reaches past the wall");
    }

    @Test
    void anOpenArenaTakesAtMostEightAndPutsThemIntoTheRound(Env env) {
        final Setup setup = setup(env, config(2, 0, 240));
        final List<Player> players = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            final Player player = setup.join("p" + i);
            setup.rules().toggleQueue(player);
            players.add(player);
        }
        tick(setup);
        tick(setup);
        assertEquals(9, queue.size(), "players were taken while the arena was prepared");
        final FakeManaged arena = setup.arenas().last();
        arena.state = ManagedInstance.State.OPEN;

        tick(setup);

        assertEquals(List.of("move p0", "move p1", "move p2", "move p3", "move p4", "move p5", "move p6", "move p7"),
                arena.requests);
        for (final Player player : players.subList(0, 8)) {
            assertEquals(RushHud.ROUND, play.scene(player));
            assertEquals(arena.instance(), player.getInstance());
        }
        assertEquals(List.of(players.get(8)), queue.players());
        assertEquals(RushHud.LOBBY, play.scene(players.get(8)));
        tick(setup);
        assertEquals(ManagedInstance.State.RUNNING, arena.state);
    }

    /** §19 "全部移入之后才开局". */
    @Test
    void theRoundStartsOnlyOnceEveryMoveIsDone(Env env) {
        final Setup setup = setup(env, config(2, 0, 240));
        final Player alice = setup.join("alice");
        final Player bob = setup.join("bob");
        setup.rules().toggleQueue(alice);
        setup.rules().toggleQueue(bob);
        tick(setup);
        final FakeManaged arena = setup.arenas().last();
        arena.holdMoves = true;
        arena.state = ManagedInstance.State.OPEN;
        tick(setup);
        assertEquals(2, arena.held.size());

        arena.finish(alice, true);
        tick(setup);
        assertEquals(ManagedInstance.State.OPEN, arena.state, "the round started with a move still under way");
        arena.finish(bob, true);
        assertEquals(ManagedInstance.State.OPEN, arena.state);
        tick(setup);

        assertEquals(ManagedInstance.State.RUNNING, arena.state);
        assertEquals(2, ((Widget.Scoreboard) play.widgets(alice).get(RushHud.SCORE)).rows().size(),
                "the first running tick did not see both players");
        assertEquals(List.of("move alice", "move bob", "start"), arena.requests);
    }

    @Test
    void aMoveThatFailsPutsThePlayerBackIntoTheLobbyScene(Env env) {
        final Setup setup = setup(env, config(2, 0, 240));
        final Player alice = setup.join("alice");
        final Player bob = setup.join("bob");
        setup.rules().toggleQueue(alice);
        setup.rules().toggleQueue(bob);
        tick(setup);
        final FakeManaged arena = setup.arenas().last();
        arena.holdMoves = true;
        arena.state = ManagedInstance.State.OPEN;
        tick(setup);
        assertEquals(RushHud.ROUND, play.scene(alice));

        arena.finish(alice, false);
        arena.finish(bob, true);
        tick(setup);

        assertEquals(RushHud.LOBBY, play.scene(alice));
        assertEquals(RushHud.hint(0), play.widgets(alice).get(RushHud.HINT));
        assertEquals(setup.lobby().instance(), alice.getInstance());
        assertNull(play.widgets(bob).get(RushHud.HINT), "a player in the round was sent the lobby hint");
        assertEquals(ManagedInstance.State.RUNNING, arena.state, "one of two moves failing stopped the round");
    }

    @Test
    void anArenaThatFailsToPrepareSendsTheQueueBackWithTheFailedHint(Env env) {
        final Setup setup = setup(env, config(2, 0, 240));
        final Player alice = setup.join("alice");
        final Player bob = setup.join("bob");
        setup.rules().toggleQueue(alice);
        setup.rules().toggleQueue(bob);
        tick(setup);
        final FakeManaged arena = setup.arenas().last();
        arena.cause = EndCause.PREPARE_FAILED;
        arena.state = ManagedInstance.State.CLOSED;

        tick(setup);

        assertEquals(0, queue.size());
        assertEquals(RushHud.hintFailed(), play.widgets(alice).get(RushHud.HINT));
        assertEquals(RushHud.hintFailed(), play.widgets(bob).get(RushHud.HINT));
        setup.rules().toggleQueue(alice);
        tick(setup);
        assertEquals(RushHud.hint(1), play.widgets(alice).get(RushHud.HINT));
        assertEquals(RushHud.hintFailed(), play.widgets(bob).get(RushHud.HINT));
        assertEquals(1, setup.arenas().specs.size(), "one player queued again started another countdown");
    }

    @Test
    void anArenaOpeningWithTooFewQueuedIsCancelledAndTheQueueStays(Env env) {
        final Setup setup = setup(env, config(2, 0, 240));
        final Player alice = setup.join("alice");
        final Player bob = setup.join("bob");
        setup.rules().toggleQueue(alice);
        setup.rules().toggleQueue(bob);
        tick(setup);
        setup.rules().toggleQueue(bob);
        final FakeManaged arena = setup.arenas().last();
        arena.state = ManagedInstance.State.OPEN;

        tick(setup);

        assertEquals(List.of("cancel"), arena.requests);
        assertEquals(List.of(alice), queue.players());
        assertEquals(RushHud.LOBBY, play.scene(alice));
    }

    /** §19 "上一局没关闭时不开第二局". */
    @Test
    void noSecondArenaWhileThePreviousRoundHasNotClosed(Env env) {
        final Setup setup = setup(env, config(2, 10, 5));
        final Player alice = setup.join("alice");
        final Player bob = setup.join("bob");
        setup.rules().toggleQueue(alice);
        setup.rules().toggleQueue(bob);
        tick(setup);
        run(setup, Duration.ofSeconds(10));
        final FakeManaged first = setup.arenas().last();
        first.state = ManagedInstance.State.OPEN;
        tick(setup);
        tick(setup);
        assertEquals(ManagedInstance.State.RUNNING, first.state);
        final Player carol = setup.join("carol");
        final Player dave = setup.join("dave");
        setup.rules().toggleQueue(carol);
        setup.rules().toggleQueue(dave);
        run(setup, Duration.ofSeconds(5));
        assertEquals(ManagedInstance.State.ENDING, first.state);

        // The second countdown started with the first running tick of the round and runs out now
        run(setup, Duration.ofSeconds(6));

        assertEquals(1, setup.arenas().specs.size(), "a second arena was created while the first round was ending");
        assertEquals(List.of(carol, dave), queue.players());
        assertNull(play.widgets(carol).get(RushHud.TIMER), "the countdown stayed on screen while it waited");
        setup.rules().backToLobby(alice);
        assertEquals(List.of("move alice"), setup.lobby().requests, "the back button did not reach the round");
        run(setup, Duration.ofSeconds(5));
        assertEquals(ManagedInstance.State.CLOSED, first.state);
        assertEquals(1, setup.arenas().specs.size(), "the countdown did not start over once the round closed");
        assertEquals(RushHud.timer("rush.timer.countdown", Duration.ofSeconds(10), true), play.widgets(carol).get(RushHud.TIMER));
        run(setup, Duration.ofSeconds(10));
        assertEquals(2, setup.arenas().specs.size());
    }

    @Test
    void leavingTheServerLeavesTheQueue(Env env) {
        final Setup setup = setup(env, config(2, 10, 240));
        final Player alice = setup.join("alice");
        final Player bob = setup.join("bob");
        setup.rules().toggleQueue(alice);
        setup.rules().toggleQueue(bob);
        tick(setup);

        setup.rules().left(alice);
        tick(setup);

        assertEquals(List.of(bob), queue.players());
        assertNull(play.widgets(bob).get(RushHud.TIMER), "the countdown went on below the minimum");
        assertInstanceOf(ActionResult.Ok.class, setup.rules().backToLobby(bob), "the button answers even with no round");
        assertFalse(setup.lobby().requests.contains("move bob"));
    }

    private Setup setup(Env env, RushConfig config) {
        final FakeManaged lobby = (FakeManaged) new FakeInstances(env, ManagedInstance.State.RUNNING)
                .create(new InstanceSpec("lobby", Arena.LOBBY_RADIUS, Arena.lobby(), Arena.SPAWN));
        final FakeInstances arenas = new FakeInstances(env, ManagedInstance.State.PREPARING);
        final Lobby rules = new Lobby(arenas, lobby, queue, config, new RushHud(play), this::record, clock::get,
                LoggerFactory.getLogger("plugin.rush"));
        return new Setup(rules, lobby, arenas, env);
    }

    private static RushConfig config(int minPlayers, int lobbyWaitSeconds, int roundSeconds) {
        return new RushConfig(minPlayers, Duration.ofSeconds(lobbyWaitSeconds), Duration.ofSeconds(roundSeconds),
                Duration.ofSeconds(10), 150);
    }

    private void tick(Setup setup) {
        clock.addAndGet(TICK.toNanos());
        setup.rules().tick();
    }

    private void run(Setup setup, Duration duration) {
        for (long ticks = duration.dividedBy(TICK); ticks > 0; ticks--) tick(setup);
    }

    /** The plugin's ledger: rush writes one event of §20 and only that one. */
    private void record(String event, Map<String, ?> attributes) {
        assertEquals("rush_round_ended", event, "rush wrote an event of §20 it does not own");
        ledger.add(attributes);
    }
}
