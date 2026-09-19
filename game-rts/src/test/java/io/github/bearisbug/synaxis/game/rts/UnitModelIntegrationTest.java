package io.github.bearisbug.synaxis.game.rts;

import io.github.bearisbug.synaxis.entity.metadata.display.ItemDisplayMeta;
import io.github.bearisbug.synaxis.game.protocol.ArchetypeBox;
import io.github.bearisbug.synaxis.game.protocol.C2SSelect;
import io.github.bearisbug.synaxis.game.protocol.S2CUnitDespawn;
import io.github.bearisbug.synaxis.game.protocol.S2CUnitSpawn;
import io.github.bearisbug.synaxis.game.protocol.S2CUnitState;
import io.github.bearisbug.synaxis.game.protocol.UnitState;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityMetaDataPacket;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static io.github.bearisbug.synaxis.game.rts.SliceFixture.MINE;
import static io.github.bearisbug.synaxis.game.rts.SliceFixture.NEUTRAL;
import static io.github.bearisbug.synaxis.game.rts.SliceFixture.THEIRS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a unit is modelled and how it becomes visible (REQ-M2-016, {@code docs/TEST.md}
 * {@code TC-GAME-052}): the five rules the display entity is built to, the interest set being the
 * engine's viewer set rather than a radius of our own, and the silence around selection.
 */
@EnvTest
class UnitModelIntegrationTest {

    @Test
    void theDisplayEntityIsBuiltToTheFiveModellingRules(Env env) {
        final SliceFixture fixture = SliceFixture.start(env);
        final Unit unit = Objects.requireNonNull(fixture.units.byId(MINE));
        final UnitEntity entity = unit.entity();
        final ItemDisplayMeta meta = (ItemDisplayMeta) entity.getEntityMeta();
        final Archetype archetype = unit.archetype();
        final ArchetypeBox box = archetype.box();

        // 1. the server states positions, it does not simulate them
        assertFalse(entity.hasPhysics(), "a unit with physics is moved by something other than its order");
        assertTrue(entity.hasNoGravity(), "a unit with gravity falls out of the path it was given");
        // 2. the position interpolation, set at spawn and never again
        assertEquals(UnitEntity.POS_ROT_INTERPOLATION_TICKS, meta.getPosRotInterpolationDuration(),
                "without it the client jumps the unit from advance to advance");
        assertEquals(0, meta.getTransformationInterpolationDuration(),
                "the transformation interpolation is the wrong one and is left alone");
        // 3. the periodic resynchronisation, widened rather than switched off
        assertEquals(UnitEntity.SYNCHRONIZATION_TICKS, entity.getSynchronizationTicks());
        // 4. the culling range, from the archetype and not from a threshold written in here
        assertEquals(archetype.viewRange(), meta.getViewRange(),
                "the view range has to come from archetype_view_range, so that one number moves it");
        // 5. a culling box, which is never zero because zero means "never cull" to the client
        assertEquals(box.width(), meta.getWidth());
        assertEquals(box.height(), meta.getHeight());
        assertTrue(box.width() > 0 && box.height() > 0);
    }

    @Test
    void aUnitInAPlayersViewerSetIsAUnitInItsInterestSet(Env env) {
        final SliceFixture fixture = SliceFixture.start(env);
        final Unit mine = Objects.requireNonNull(fixture.units.byId(MINE));

        // The fixture spawned three units while the player was already in the instance, so the
        // engine made it a viewer of each and the announcement hangs off that and nothing else
        final List<S2CUnitSpawn> spawns = fixture.channels.of(S2CUnitSpawn.class);
        assertEquals(List.of(MINE, NEUTRAL, THEIRS), spawns.stream().map(S2CUnitSpawn::unitId).toList());
        assertTrue(mine.entity().getViewers().contains(fixture.player));

        final S2CUnitSpawn spawn = spawns.getFirst();
        assertEquals(mine.entity().getEntityId(), spawn.entityId(),
                "the message binds unit_id to entity_id, which is the client's only way to find the model");
        assertEquals(mine.archetype().id(), spawn.archetype());
        assertEquals(Unit.MAX_HP, spawn.hp());
        assertEquals(S2CUnitSpawn.FLAG_COMMANDABLE, spawn.flags() & S2CUnitSpawn.FLAG_COMMANDABLE,
                "a unit of the player's own team is the player's to command");
        assertEquals(0, spawns.get(1).flags(), "a neutral unit is nobody's to command");
        assertEquals(0, spawns.get(2).flags(), "a unit of the other team is not this player's");
    }

    @Test
    void aUnitLeavingTheBoardIsDespawnedFromEveryInterestSetItWasIn(Env env) {
        final SliceFixture fixture = SliceFixture.start(env);
        assertEquals(3, fixture.channels.of(S2CUnitSpawn.class).size());

        fixture.units.end();

        assertEquals(List.of(MINE, NEUTRAL, THEIRS), fixture.channels.of(S2CUnitDespawn.class).stream()
                .map(despawn -> despawn.unitIds().getFirst()).toList());
        assertNull(fixture.units.byId(MINE));
        assertEquals(0, fixture.match.unitTeam(MINE),
                "the team table still names a unit the board no longer has, so owner(unit, sender) "
                        + "and the units themselves disagree about what is on it");
    }

    @Test
    void aSnapshotCarriesEverythingThisPlayerCanSeeAndNothingElse(Env env) {
        final SliceFixture fixture = SliceFixture.start(env);
        fixture.channels.clear();

        fixture.tick(Units.ADVANCE_INTERVAL_TICKS);

        final List<S2CUnitState> snapshots = fixture.channels.of(S2CUnitState.class);
        assertEquals(1, snapshots.size(), "one snapshot per player per advance, no more and no fewer");
        assertEquals(List.of(MINE, NEUTRAL, THEIRS),
                snapshots.getFirst().entries().stream().map(S2CUnitState.Entry::unitId).toList());
        assertTrue(snapshots.getFirst().entries().stream()
                        .allMatch(entry -> entry.unitState() == UnitState.IDLE && entry.hp() == Unit.MAX_HP),
                "a unit that was never ordered anywhere is idle at full health");
    }

    @Test
    void selectingAUnitSendsNoEntityMetadataAtAll(Env env) {
        final SliceFixture fixture = SliceFixture.start(env);
        final Collector<EntityMetaDataPacket> metadata = fixture.connection.trackIncoming(EntityMetaDataPacket.class);

        fixture.receive(new C2SSelect(List.of(MINE)));
        fixture.tick(Units.ADVANCE_INTERVAL_TICKS);

        assertNotNull(fixture.sessions.get(fixture.connectionId()));
        assertEquals(List.of(MINE), List.copyOf(
                Objects.requireNonNull(fixture.sessions.get(fixture.connectionId())).selection()));
        // Entity metadata is global to the entity: a glow set for the player who selected the unit
        // would light it up for everyone watching, which is why the box is the client's to draw
        metadata.assertEmpty();
    }
}
