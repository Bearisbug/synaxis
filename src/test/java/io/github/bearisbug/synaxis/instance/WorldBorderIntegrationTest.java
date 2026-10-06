package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.network.packet.server.play.WorldBorderLerpSizePacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class WorldBorderIntegrationTest {

    @Test
    public void setWorldborderSize(Env env) {
        Instance instance = env.createFlatInstance();

        instance.setWorldBorder(WorldBorder.defaultBorder().withDiameter(50));
        assertEquals(50, instance.getWorldBorder().diameter());
        instance.setWorldBorder(WorldBorder.defaultBorder().withDiameter(10));
        assertEquals(10, instance.getWorldBorder().diameter());
    }

    @Test
    public void resizeWorldBorder(Env env) {
        Instance instance = env.createFlatInstance();

        WorldBorder border = instance.getWorldBorder();
        instance.setWorldBorder(border.withDiameter(10));
        assertEquals(10, instance.getWorldBorder().diameter());

        // Lerp
        instance.setWorldBorder(border.withDiameter(30), 1);
        for (int i = 0; i < 10; i++) {
            assertEquals(10 + i, instance.getWorldBorder().diameter());
            instance.tick(0);
        }

        // Lerp from another diameter mid lerp
        instance.setWorldBorder(border.withDiameter(25), 0.25);
        for (int i = 0; i < 5; i++) {
            assertEquals(20 + i, instance.getWorldBorder().diameter());
            instance.tick(0);
        }

        // Ensure lerp finished
        for (int i = 0; i < 4; i++) {
            assertEquals(25, instance.getWorldBorder().diameter());
            instance.tick(0);
        }
    }

    /**
     * The 26.2 client counts the lerp time of {@code set_border_lerp_size} and {@code initialize_border} down by one
     * every client tick ({@code WorldBorder$MovingBorderExtent#update}), so both packets carry ticks.
     */
    @Test
    public void transitionTimeIsSentInTicks(Env env) {
        Instance instance = env.createFlatInstance();
        var connection = env.createConnection();
        connection.connect(instance, new Pos(0, 42, 0));

        final long ticksPerSecond = 1000 / MinecraftServer.TICK_MS;
        WorldBorder border = instance.getWorldBorder();
        instance.setWorldBorder(border.withDiameter(30));
        var lerp = connection.trackIncoming(WorldBorderLerpSizePacket.class);
        instance.setWorldBorder(border.withDiameter(10), 1);
        lerp.assertSingle(packet -> assertEquals(ticksPerSecond, packet.speed(), "lerp time of a 1 s transition"));

        for (int i = 0; i < 5; i++) instance.tick(0);
        assertEquals(ticksPerSecond - 5, instance.createInitializeWorldBorderPacket().speed(),
                "lerp time left for a player joining 5 ticks in");
    }

    @Test
    public void invalidArguments(Env env) {
        Instance instance = env.createFlatInstance();

        WorldBorder border = instance.getWorldBorder();
        assertThrows(IllegalStateException.class, () -> instance.setWorldBorder(border, -1));
        assertThrows(IllegalArgumentException.class, () -> border.withDiameter(-1));
        assertThrows(IllegalArgumentException.class, () -> border.withDiameter(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> border.withCenter(Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> border.withCenter(0, Double.NaN));
    }

    @Test
    public void pointInBoundsExcludesMaximumBound() {
        WorldBorder border = new WorldBorder(4, 0, 0, 0, 0);
        assertTrue(border.inBounds(new Vec(-2, 0, -2)));
        assertTrue(border.inBounds(new Vec(1.999, 0, 1.999)));
        assertFalse(border.inBounds(new Vec(2, 0, 0)));
        assertFalse(border.inBounds(new Vec(0, 0, 2)));
    }

    @Test
    public void entityBoundsIncludeBoundingBox(Env env) {
        Instance instance = env.createFlatInstance();
        WorldBorder border = new WorldBorder(4, 0, 0, 0, 0);
        Entity entity = new Entity(EntityType.ZOMBIE);
        double maximumEntityX = 2 - entity.getBoundingBox().maxX();
        entity.setInstance(instance, new Pos(maximumEntityX, 42, 0)).join();

        assertTrue(border.inBounds(entity.getPosition(), entity.getBoundingBox()));
        entity.refreshPosition(new Pos(maximumEntityX + Vec.EPSILON, 42, 0));
        assertFalse(border.inBounds(entity.getPosition(), entity.getBoundingBox()));
    }
}
