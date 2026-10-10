package io.github.bearisbug.synaxis.game.rts;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deploying a roster of a given size: the thousand units of the §15 load profile fit on the small
 * map and are numbered the way the default roster is, and a roster the map cannot hold is refused
 * before a single unit is on the board.
 */
@EnvTest
class RosterIntegrationTest {

    private record Board(Match match, NavGrid grid, MapPreset preset, InstanceContainer instance, Units units) {

        static Board of(Env env) {
            final RecordedEvents events = new RecordedEvents();
            final Match match = new Match(env.process(), MatchSupport.registerMatchDimension(env), events,
                    LoggerFactory.getLogger(RosterIntegrationTest.class));
            match.load(MapPreset.SMALL.id());
            final NavGrid grid = Objects.requireNonNull(match.navGrid(), "the match did not come up");
            final MapPreset preset = Objects.requireNonNull(match.preset());
            final InstanceContainer instance = Objects.requireNonNull(match.instance());
            final AtomicLong nanos = new AtomicLong();
            final Units units = new Units(match, instance, grid, preset,
                    new GameSessions(env.process().connection()), new RecordedChannels(), events, nanos::get);
            return new Board(match, grid, preset, instance, units);
        }
    }

    @Test
    void aThousandUnitsAreDeployedOverTheMapAndNumberedLikeTheDefaultRoster(Env env) {
        final Board board = Board.of(env);
        Roster.deploy(board.units(), board.grid(), board.preset(), board.match().spawn(), 1000);

        assertEquals(1000, board.instance().getEntities().size(), "the whole roster is on the board");
        for (int unitId = 1; unitId <= 997; unitId++) {
            final Unit own = board.units().byId(unitId);
            assertNotNull(own, "unit " + unitId);
            assertEquals(Roster.PLAYER_TEAM, own.team(), "unit " + unitId + " is the player's");
            final Pos position = own.entity().getPosition();
            assertTrue(board.grid().isWalkable((int) Math.floor(position.x()), (int) Math.floor(position.z())),
                    "unit " + unitId + " stands on a column nothing can walk on: " + position);
        }
        assertEquals(0, Objects.requireNonNull(board.units().byId(998)).team(), "the neutral unit follows the player's");
        assertEquals(Roster.ENEMY_TEAM, Objects.requireNonNull(board.units().byId(999)).team(), "then the enemy");
        assertEquals(Roster.PLAYER_TEAM, Objects.requireNonNull(board.units().byId(1000)).team(), "then the outpost");
        assertNull(board.units().byId(1001), "nothing past the number asked for");
        assertEquals(Roster.PLAYER_TEAM, board.match().unitTeam(500), "the match's own table knows the roster too");
    }

    @Test
    void aRosterTheMapCannotHoldIsRefusedWithNothingOnTheBoard(Env env) {
        final Board board = Board.of(env);
        final int tooMany = board.preset().sizeX() * board.preset().sizeZ() + Roster.FIXED_UNITS;
        final IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> Roster.deploy(board.units(), board.grid(), board.preset(), board.match().spawn(), tooMany));
        assertTrue(refused.getMessage().contains(Roster.UNITS_PROPERTY + "=" + tooMany), refused.getMessage());
        assertTrue(refused.getMessage().contains("map_id=" + board.preset().id()), refused.getMessage());
        assertEquals(0, board.instance().getEntities().size(), "a roster that does not fit is not half deployed");
        assertNull(board.units().byId(1));
    }
}
