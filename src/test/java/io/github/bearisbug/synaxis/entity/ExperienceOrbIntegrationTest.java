package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.network.packet.server.play.SpawnEntityPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class ExperienceOrbIntegrationTest {

    @Test
    public void orbWithoutGravityStaysInTheAir(Env env) {
        var instance = env.createFlatInstance();
        var orb = new ExperienceOrb((short) 3);
        orb.setNoGravity(true);
        orb.setInstance(instance, new Pos(0.5, 45, 0.5)).join();

        for (int i = 0; i < 20; i++) env.tick();

        assertEquals(45, orb.getPosition().y());
        assertEquals(Vec.ZERO, orb.getVelocity());
    }

    @Test
    public void orbFallsOntoTheGround(Env env) {
        var instance = env.createFlatInstance();
        var orb = new ExperienceOrb((short) 3);
        orb.setInstance(instance, new Pos(0.5, 42, 0.5)).join();

        for (int i = 0; i < 20; i++) env.tick();

        assertEquals(40, orb.getPosition().y(), 1e-6);
        assertTrue(orb.isOnGround());
    }

    @Test
    public void experienceCountKeepsTheViewers(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var nearby = connection.connect(instance, new Pos(0.5, 40, 2));
        var far = env.createPlayer(instance, new Pos(0.5, 40, 5_000));
        var orb = new ExperienceOrb((short) 3);
        orb.setInstance(instance, new Pos(0.5, 40, 0.5)).join();
        assertTrue(orb.addViewer(far));
        assertTrue(orb.isViewer(nearby));

        var spawns = connection.trackIncoming(SpawnEntityPacket.class);
        orb.setExperienceCount((short) 5);

        assertEquals(5, orb.getExperienceCount());
        assertTrue(orb.isViewer(far), "the manual viewer still sees the orb");
        assertTrue(orb.isViewer(nearby));
        spawns.assertNoneMatch(packet -> packet.entityId() == orb.getEntityId());

        // The nearby player is still an automatic viewer: walking away drops it
        nearby.teleport(new Pos(0.5, 40, 5_000)).join();
        assertFalse(orb.isViewer(nearby));
    }
}
