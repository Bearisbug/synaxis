package io.github.bearisbug.synaxis.game.rts;

import io.github.bearisbug.synaxis.game.protocol.S2CUnitState;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.github.bearisbug.synaxis.game.rts.SliceFixture.OURS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A view with more units in it than one {@code S2C_UNIT_STATE} carries ({@code docs/DESIGN-M2.md}
 * §15, the unit snapshot row; {@code docs/TEST.md} {@code TC-GAME-068}): each snapshot holds at most
 * sixty lines, and the units take turns, so that every one of them is refreshed within
 * ⌈n ÷ 60⌉ snapshots.
 */
@EnvTest
class UnitSnapshotIntegrationTest {

    /** §15: the most lines one snapshot carries. */
    private static final int LINES = 60;

    /** The view {@code TC-GAME-068} works the refresh interval out for: 282 units around one player. */
    private static final int CROWD = 282;

    /** Past the {@code count} cap of §9, which is where a snapshot without turns drops units for good. */
    private static final int THRONG = 600;

    @Test
    void aSnapshotCarriesNoMoreThanSixtyLines(Env env) {
        final SliceFixture fixture = crowd(env, CROWD);

        final List<S2CUnitState> snapshots = snapshots(fixture, 5);

        for (final S2CUnitState snapshot : snapshots) {
            assertEquals(LINES, snapshot.entries().size(),
                    "a view of " + CROWD + " units has to be sent sixty lines at a time");
        }
    }

    @Test
    void everyUnitInTheViewTakesItsTurn(Env env) {
        final SliceFixture fixture = crowd(env, THRONG);
        final S2CUnitState first = snapshots(fixture, 1).getFirst();

        // The unit the first snapshot ended on leaves the view: the next snapshot starts at the first
        // unit after it all the same. Starting over from the lowest unit_id instead would still
        // cover the whole view in ten snapshots, but a view whose last unit kept leaving would
        // never get past its first sixty
        final int gone = first.entries().getLast().unitId();
        Objects.requireNonNull(fixture.units.byId(gone)).entity().setAutoViewable(false);
        fixture.channels.clear();
        final int left = THRONG - 1;
        final List<S2CUnitState> turns = snapshots(fixture, Math.ceilDiv(left, LINES));

        assertEquals(gone + 1, turns.getFirst().entries().getFirst().unitId(),
                "the turn after unit " + gone + " left the view did not start right after it");
        final Set<Integer> missed = IntStream.rangeClosed(1, THRONG).boxed()
                .filter(unitId -> unitId != gone)
                .collect(Collectors.toCollection(TreeSet::new));
        turns.forEach(snapshot -> snapshot.entries().forEach(entry -> missed.remove(entry.unitId())));
        assertTrue(missed.isEmpty(), Math.ceilDiv(left, LINES) + " snapshots of a view of " + left
                + " units never carried " + missed.size() + " of them: " + missed);
        assertTrue(turns.stream().noneMatch(snapshot -> snapshot.entries().stream()
                .anyMatch(entry -> entry.unitId() == gone)), "a unit out of the view was still sent");
    }

    @Test
    void noUnitWaitsLongerThanItsShareOfTheTurns(Env env) {
        final SliceFixture fixture = crowd(env, CROWD);
        final int rounds = 50;

        final List<S2CUnitState> snapshots = snapshots(fixture, rounds);

        // 282 units sixty at a time: every unit is in 60 / 282 of the snapshots, once in 4.7 of
        // them on average, which at 10 Hz is 470 ms, and never further apart than five of them
        // (500 ms), counting from the first snapshot as well
        final int bound = Math.ceilDiv(CROWD, LINES);
        final Map<Integer, List<Integer>> carriedIn = new HashMap<>();
        for (int i = 0; i < snapshots.size(); i++) {
            for (final S2CUnitState.Entry entry : snapshots.get(i).entries()) {
                carriedIn.computeIfAbsent(entry.unitId(), _ -> new ArrayList<>()).add(i);
            }
        }
        assertEquals(CROWD, carriedIn.size(), "units that never had a turn");
        for (final Map.Entry<Integer, List<Integer>> unit : carriedIn.entrySet()) {
            final List<Integer> at = unit.getValue();
            assertTrue(at.getFirst() < bound, "unit " + unit.getKey() + " waited until snapshot "
                    + at.getFirst() + " for its first turn");
            for (int i = 1; i < at.size(); i++) {
                assertTrue(at.get(i) - at.get(i - 1) <= bound, "unit " + unit.getKey() + " went "
                        + (at.get(i) - at.get(i - 1)) + " snapshots without a line, the bound is " + bound);
            }
            assertTrue(rounds - 1 - at.getLast() < bound, "unit " + unit.getKey()
                    + " had no turn in the last " + (rounds - 1 - at.getLast()) + " snapshots");
            final int share = rounds * LINES / CROWD;
            assertTrue(at.size() == share || at.size() == share + 1, "unit " + unit.getKey() + " is in "
                    + at.size() + " of " + rounds + " snapshots, its share is " + share + " or " + (share + 1));
        }
    }

    /**
     * The slice fixture with as many units of the player's team around its spawn as it takes to
     * put {@code view} units in the player's view, all of them announced, and nothing recorded yet.
     */
    private static SliceFixture crowd(Env env, int view) {
        final SliceFixture fixture = SliceFixture.start(env);
        for (int i = 3; i < view; i++) fixture.units.spawn(Archetype.SOLDIER, OURS, fixture.match.spawn());
        fixture.tick(Units.ADVANCE_INTERVAL_TICKS);
        final List<S2CUnitState> announced = fixture.channels.of(S2CUnitState.class);
        assertEquals(1, announced.size());
        fixture.channels.clear();
        return fixture;
    }

    /** Runs the given number of snapshots and returns what they sent to the player. */
    private static List<S2CUnitState> snapshots(SliceFixture fixture, int count) {
        fixture.tick(count * Units.ADVANCE_INTERVAL_TICKS);
        final List<S2CUnitState> sent = fixture.channels.of(S2CUnitState.class);
        assertEquals(count, sent.size(), "one snapshot per advance");
        return sent;
    }
}
