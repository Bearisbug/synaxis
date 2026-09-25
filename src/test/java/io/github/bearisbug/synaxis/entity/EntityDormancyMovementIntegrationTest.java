package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityPositionAndRotationPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityPositionSyncPacket;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A dormant entity that still moves on the ticks it runs ({@code docs/DESIGN-PROD.md}
 * {@code REQ-PROD-040}, {@code API-PROD-019}): its viewers see every step, not only the scheduled
 * synchronization it no longer sends.
 */
@EnvTest
public class EntityDormancyMovementIntegrationTest {

    private static final Pos SPAWN = new Pos(0.5, 42, 0.5);
    private static final double STEP = 0.05;

    /** Walks along x on every tick it runs, as a plugin's pathfinding moves an entity. */
    static final class Walking extends Entity {
        final AtomicInteger steps = new AtomicInteger();

        Walking() {
            super(EntityType.ZOMBIE);
            setNoGravity(true);
        }

        @Override
        public void update(long time) {
            steps.incrementAndGet();
            moveTo(getPosition().add(STEP, 0, 0));
        }
    }

    /** The §19 scenario: every 4 ticks with the player 40 blocks away, for 4 windows of 100 ticks. */
    @Test
    public void aDormantEntityThatMovesSendsEveryStepToItsViewers(Env env) {
        final Instance instance = env.createFlatInstance();
        final TestConnection connection = env.createConnection();
        connection.connect(instance, SPAWN);
        final Walking entity = new Walking();
        entity.setInstance(instance, new Pos(40.5, 42, 0.5)).join();
        entity.setDormancyPolicy((_, distance) -> distance > 32 ? 4 : 1);
        // The first tick asks the instance for the players, which it has from the next tick on
        env.tick();
        env.tick();

        for (int window = 0; window < 4; window++) {
            final int stepsBefore = entity.steps.get();
            final double xBefore = entity.getPosition().x();
            final Collector<EntityPositionAndRotationPacket> moves =
                    connection.trackIncoming(EntityPositionAndRotationPacket.class);
            final Collector<EntityPositionSyncPacket> syncs = connection.trackIncoming(EntityPositionSyncPacket.class);
            for (int i = 0; i < 100; i++) env.tick();

            final int steps = entity.steps.get() - stepsBefore;
            assertEquals(25, steps, "window " + window + ": it runs once every 4 ticks");
            assertEquals(steps * STEP, entity.getPosition().x() - xBefore, 1e-9,
                    "window " + window + ": the server moved it");
            final List<EntityPositionAndRotationPacket> ofEntity = moves.collect().stream()
                    .filter(packet -> packet.entityId() == entity.getEntityId()).toList();
            assertEquals(steps, ofEntity.size(), "window " + window + ": a relative move for every step");
            assertEquals(0, syncs.collect().stream()
                    .filter(packet -> packet.entityId() == entity.getEntityId()).count(),
                    "window " + window + ": no scheduled synchronization while dormant");
            // Each delta is truncated to 1/4096 of a block, nothing corrects it while dormant
            final double seen = ofEntity.stream().mapToInt(EntityPositionAndRotationPacket::deltaX).sum() / 4096.0;
            assertEquals(steps * STEP, seen, steps / 4096.0,
                    "window " + window + ": the viewer sees it move as far as the server did");
        }
    }
}
