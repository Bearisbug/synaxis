package dev.gameengine.collision;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;

public class CollisionShapeInternTest {

    // Two registry entries can spell the same shape differently, they must end up on one instance
    @Test
    public void equalShapesShareOneInstance() {
        Map<Object, Object> internCache = new HashMap<>();
        Shape first = CollisionUtils.parseCollisionShape(internCache, "0.0 0.0 0.0 1.0 1.0 1.0");
        Shape second = CollisionUtils.parseCollisionShape(internCache, "0.00 0.00 0.00 1.00 1.00 1.00");
        assertSame(first, second);
        assertSame(first, CollisionUtils.parseCollisionShape(internCache, "0.00 0.00 0.00 1.00 1.00 1.00"));
    }

    @Test
    public void equalOcclusionShapesShareOneInstance() {
        Map<Object, Object> internCache = new HashMap<>();
        Shape first = CollisionUtils.parseOcclusionShape(internCache, "0.0 0.0 0.0 1.0 1.0 1.0", true, (byte) 0);
        Shape second = CollisionUtils.parseOcclusionShape(internCache, "0.00 0.00 0.00 1.00 1.00 1.00", true, (byte) 0);
        assertSame(first, second);
        assertSame(first, CollisionUtils.parseOcclusionShape(internCache, "0.00 0.00 0.00 1.00 1.00 1.00", true, (byte) 0));
    }
}
