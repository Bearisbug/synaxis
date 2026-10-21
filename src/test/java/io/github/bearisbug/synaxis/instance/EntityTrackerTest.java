package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class EntityTrackerTest {
    @Test
    public void register() {
        var ent1 = new Entity(EntityType.ZOMBIE);
        var updater = new EntityTracker.Update<>() {
            @Override
            public void add(Entity entity) {
                assertNotSame(ent1, entity);
                fail("No other entity should be registered yet");
            }

            @Override
            public void remove(Entity entity) {
                assertNotSame(ent1, entity);
                fail("No other entity should be registered yet");
            }
        };
        EntityTracker tracker = EntityTracker.newTracker(ServerConfig.defaults().entityViewDistance());
        var chunkEntities = tracker.chunkEntities(Vec.ZERO, EntityTracker.Target.ENTITIES);
        assertTrue(chunkEntities.isEmpty());

        tracker.register(ent1, Vec.ZERO, EntityTracker.Target.ENTITIES, updater);
        assertEquals(1, tracker.chunkEntities(Vec.ZERO, EntityTracker.Target.ENTITIES).size());

        tracker.unregister(ent1, EntityTracker.Target.ENTITIES, updater);
        assertEquals(0, tracker.chunkEntities(Vec.ZERO, EntityTracker.Target.ENTITIES).size());
    }

    @Test
    public void move() {
        var ent1 = new Entity(EntityType.ZOMBIE);
        var updater = new EntityTracker.Update<>() {
            @Override
            public void add(Entity entity) {
                fail("No other entity should be registered yet");
            }

            @Override
            public void remove(Entity entity) {
                fail("No other entity should be registered yet");
            }
        };

        EntityTracker tracker = EntityTracker.newTracker(ServerConfig.defaults().entityViewDistance());

        tracker.register(ent1, Vec.ZERO, EntityTracker.Target.ENTITIES, updater);
        assertEquals(1, tracker.chunkEntities(Vec.ZERO, EntityTracker.Target.ENTITIES).size());

        tracker.move(ent1, new Vec(32, 0, 32), EntityTracker.Target.ENTITIES, updater);
        assertEquals(0, tracker.chunkEntities(Vec.ZERO, EntityTracker.Target.ENTITIES).size());
        assertEquals(1, tracker.chunkEntities(new Vec(32, 0, 32), EntityTracker.Target.ENTITIES).size());
    }

    @Test
    public void tracking() {
        var ent1 = new Entity(EntityType.ZOMBIE);
        var ent2 = new Entity(EntityType.ZOMBIE);

        EntityTracker tracker = EntityTracker.newTracker(ServerConfig.defaults().entityViewDistance());
        tracker.register(ent1, Vec.ZERO, EntityTracker.Target.ENTITIES, new EntityTracker.Update<>() {
            @Override
            public void add(Entity entity) {
                fail("No other entity should be registered yet");
            }

            @Override
            public void remove(Entity entity) {
                fail("No other entity should be registered yet");
            }
        });

        tracker.register(ent2, Vec.ZERO, EntityTracker.Target.ENTITIES, new EntityTracker.Update<>() {
            @Override
            public void add(Entity entity) {
                assertNotSame(ent2, entity);
                assertSame(ent1, entity);
            }

            @Override
            public void remove(Entity entity) {
                fail("No other entity should be removed yet");
            }
        });

        tracker.move(ent1, new Vec(Integer.MAX_VALUE, 0, 0), EntityTracker.Target.ENTITIES, new EntityTracker.Update<>() {
            @Override
            public void add(Entity entity) {
                assertNotSame(ent1, entity);
                fail("No other entity should be added");
            }

            @Override
            public void remove(Entity entity) {
                assertNotSame(ent1, entity);
                assertSame(ent2, entity);
            }
        });

        tracker.move(ent1, Vec.ZERO, EntityTracker.Target.ENTITIES, new EntityTracker.Update<>() {
            @Override
            public void add(Entity entity) {
                assertNotSame(ent1, entity);
                assertSame(ent2, entity);
            }

            @Override
            public void remove(Entity entity) {
                fail("no entity to remove");
            }
        });
    }

    @Test
    public void nearby() {
        var ent1 = new Entity(EntityType.ZOMBIE);
        var ent2 = new Entity(EntityType.ZOMBIE);
        var ent3 = new Entity(EntityType.ZOMBIE);
        var updater = new EntityTracker.Update<>() {
            @Override
            public void add(Entity entity) {
                // Empty
            }

            @Override
            public void remove(Entity entity) {
                // Empty
            }
        };

        EntityTracker tracker = EntityTracker.newTracker(ServerConfig.defaults().entityViewDistance());
        tracker.register(ent2, new Vec(5, 0, 0), EntityTracker.Target.ENTITIES, updater);
        tracker.register(ent3, new Vec(50, 0, 0), EntityTracker.Target.ENTITIES, updater);

        tracker.nearbyEntities(Vec.ZERO, 4, EntityTracker.Target.ENTITIES, _ -> fail("No entity should be nearby"));

        tracker.register(ent1, Vec.ZERO, EntityTracker.Target.ENTITIES, updater);

        Set<Entity> entities = new HashSet<>();

        entities.add(ent1);
        tracker.nearbyEntities(Vec.ZERO, 4, EntityTracker.Target.ENTITIES, entity -> assertTrue(entities.remove(entity)));
        assertEquals(0, entities.size());

        entities.add(ent1);
        tracker.nearbyEntities(Vec.ZERO, 4.99, EntityTracker.Target.ENTITIES, entity -> assertTrue(entities.remove(entity)));
        assertEquals(0, entities.size());

        entities.add(ent1);
        entities.add(ent2);
        tracker.nearbyEntities(Vec.ZERO, 5, EntityTracker.Target.ENTITIES, entity -> assertTrue(entities.remove(entity)));
        assertEquals(0, entities.size());

        entities.add(ent1);
        entities.add(ent2);
        entities.add(ent3);
        tracker.nearbyEntities(Vec.ZERO, 50, EntityTracker.Target.ENTITIES, entity -> assertTrue(entities.remove(entity)));
        assertEquals(0, entities.size());

        // Chunk border
        tracker.move(ent1, new Vec(16, 0, 0), EntityTracker.Target.ENTITIES, updater);
        entities.add(ent1);
        tracker.nearbyEntities(new Vec(15, 0, 0), 2, EntityTracker.Target.ENTITIES, entity -> assertTrue(entities.remove(entity)));
        assertEquals(0, entities.size());
    }

    @Test
    public void nearbyWithARangeBeyondTheChunkRangeFindsEveryEntity() {
        var near = new Entity(EntityType.ZOMBIE);
        var far = new Entity(EntityType.ZOMBIE);
        var updater = new EntityTracker.Update<>() {
            @Override
            public void add(Entity entity) {
                // Empty
            }

            @Override
            public void remove(Entity entity) {
                // Empty
            }
        };
        EntityTracker tracker = EntityTracker.newTracker(ServerConfig.defaults().entityViewDistance());
        tracker.register(near, Vec.ZERO, EntityTracker.Target.ENTITIES, updater);
        tracker.register(far, new Vec(1600, 0, 1600), EntityTracker.Target.ENTITIES, updater);

        for (double range : new double[]{Double.MAX_VALUE, Double.POSITIVE_INFINITY, 1e6, 1e5}) {
            Set<Entity> found = new HashSet<>();
            tracker.nearbyEntities(Vec.ZERO, range, EntityTracker.Target.ENTITIES, found::add);
            assertEquals(Set.of(near, far), found, () -> "range " + range);
        }
        Set<Entity> found = new HashSet<>();
        tracker.nearbyEntities(Vec.ZERO, 2000, EntityTracker.Target.ENTITIES, found::add);
        assertEquals(Set.of(near), found);
    }

    @Test
    public void nearbyWithANegativeOrNaNRangeFindsNothing() {
        var updater = new EntityTracker.Update<>() {
            @Override
            public void add(Entity entity) {
                // Empty
            }

            @Override
            public void remove(Entity entity) {
                // Empty
            }
        };
        EntityTracker tracker = EntityTracker.newTracker(ServerConfig.defaults().entityViewDistance());
        // One query point well inside its chunk, one next to the chunk edge, each with an entity 0.1 away
        var inside = new Entity(EntityType.ZOMBIE);
        var atEdge = new Entity(EntityType.ZOMBIE);
        tracker.register(inside, new Vec(8.1, 0, 8), EntityTracker.Target.ENTITIES, updater);
        tracker.register(atEdge, new Vec(15.6, 0, 8), EntityTracker.Target.ENTITIES, updater);

        for (Vec point : new Vec[]{new Vec(8, 0, 8), new Vec(15.5, 0, 8)}) {
            for (double range : new double[]{-1, -0.5, -20, Double.NaN}) {
                Set<Entity> found = new HashSet<>();
                tracker.nearbyEntities(point, range, EntityTracker.Target.ENTITIES, found::add);
                assertEquals(Set.of(), found, () -> "range " + range + " at " + point);
            }
        }
    }

    @Test
    public void nearbyWithMoreEntitiesThanChunksInRange() {
        var updater = new EntityTracker.Update<>() {
            @Override
            public void add(Entity entity) {
                // Empty
            }

            @Override
            public void remove(Entity entity) {
                // Empty
            }
        };
        EntityTracker tracker = EntityTracker.newTracker(ServerConfig.defaults().entityViewDistance());
        Set<Entity> expected = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            var entity = new Entity(EntityType.ZOMBIE);
            tracker.register(entity, new Vec(i % 2 == 0 ? 1 : 20, 0, 0), EntityTracker.Target.ENTITIES, updater);
            expected.add(entity);
        }
        var outside = new Entity(EntityType.ZOMBIE);
        tracker.register(outside, new Vec(0, 0, 40), EntityTracker.Target.ENTITIES, updater);

        // Range 20 spans 5 x 5 chunks, fewer than the 31 entities
        Set<Entity> found = new HashSet<>();
        tracker.nearbyEntities(Vec.ZERO, 20, EntityTracker.Target.ENTITIES, found::add);
        assertEquals(expected, found);
    }

    @Test
    public void nearbyEntitiesAcrossAChunkEdge() {
        var inside = new Entity(EntityType.ZOMBIE);
        var outside = new Entity(EntityType.ZOMBIE);
        EntityTracker tracker = EntityTracker.newTracker(ServerConfig.defaults().entityViewDistance());
        tracker.register(inside, new Vec(16.3, 0, 0), EntityTracker.Target.ENTITIES, null);
        tracker.register(outside, new Vec(16.6, 0, 0), EntityTracker.Target.ENTITIES, null);

        Set<Entity> found = new HashSet<>();
        tracker.nearbyEntities(new Vec(15.9, 0, 0), 0.5, EntityTracker.Target.ENTITIES, found::add);
        assertEquals(Set.of(inside), found);
    }

    // A huge range takes the walk over the chunk buckets: its square of chunks has up to 2^56 of them
    @Test
    public void nearbyEntitiesWithHugeRangeFindsAll() {
        EntityTracker tracker = EntityTracker.newTracker(ServerConfig.defaults().entityViewDistance());
        final Map<Entity, Point> positions = new LinkedHashMap<>();
        for (Point point : List.of(Vec.ZERO, new Vec(-30_000_000, 300, 29_999_999), new Vec(1e9, -1e9, 1e9))) {
            var entity = new Entity(EntityType.ZOMBIE);
            tracker.register(entity, point, EntityTracker.Target.ENTITIES, null);
            positions.put(entity, point);
        }
        for (double range : new double[]{Double.MAX_VALUE, 1e300, 1e12, 1e6, 4e5}) {
            for (Point center : List.of(Vec.ZERO, new Vec(29_999_999.5, 0, -29_999_999.5))) {
                final Set<Entity> expected = new HashSet<>();
                positions.forEach((entity, point) -> {
                    if (center.distanceSquared(point) <= range * range) expected.add(entity);
                });
                Set<Entity> found = new HashSet<>();
                assertTimeoutPreemptively(Duration.ofSeconds(5),
                        () -> tracker.nearbyEntities(center, range, EntityTracker.Target.ENTITIES, found::add),
                        () -> "range " + range + " around " + center);
                assertEquals(expected, found, () -> "range " + range + " around " + center);
            }
        }
        Set<Entity> found = new HashSet<>();
        tracker.nearbyEntities(Vec.ZERO, Double.MAX_VALUE, EntityTracker.Target.ENTITIES, found::add);
        assertEquals(positions.keySet(), found);
    }

    // Both queries against a scan of every entity, over ranges taking the chunk loop and the walk over the buckets
    @Test
    public void nearbyQueriesMatchABruteForceScan() {
        final Random random = new Random(20261021L);
        EntityTracker tracker = EntityTracker.newTracker(ServerConfig.defaults().entityViewDistance());
        final Map<Entity, Point> positions = new LinkedHashMap<>();
        for (int i = 0; i < 300; i++) {
            var entity = new Entity(EntityType.ZOMBIE);
            // A third on a chunk edge or just before one
            final double x = i % 3 == 0 ? 16 * (random.nextInt(15) - 7) - (i % 2 == 0 ? 0 : 1e-4)
                    : random.nextDouble(-120, 120);
            final Point point = new Vec(x, random.nextDouble(-40, 40), random.nextDouble(-120, 120));
            tracker.register(entity, point, EntityTracker.Target.ENTITIES, null);
            positions.put(entity, point);
        }
        // Half of them move, within their chunk or into another one
        for (Entity entity : List.copyOf(positions.keySet())) {
            if (random.nextBoolean()) continue;
            final Point point = positions.get(entity).add(random.nextDouble(-20, 20), 0, random.nextDouble(-20, 20));
            tracker.move(entity, point, EntityTracker.Target.ENTITIES, null);
            positions.put(entity, point);
        }

        final List<Point> centers = new ArrayList<>(List.of(Vec.ZERO, new Vec(15.9, 0, 0), new Vec(16, 3, 16),
                new Vec(-1e-4, -5, -16)));
        for (int i = 0; i < 30; i++) {
            centers.add(new Vec(random.nextDouble(-140, 140), random.nextDouble(-50, 50), random.nextDouble(-140, 140)));
        }
        for (Point center : centers) {
            for (double range : new double[]{0, 0.5, 1, 4.99, 8, 15.99, 16, 16.01, 17, 23.5, 32, 40, 64, 100, 150, 1000}) {
                final Set<Entity> expected = new HashSet<>();
                positions.forEach((entity, point) -> {
                    if (center.distanceSquared(point) <= range * range) expected.add(entity);
                });
                final List<Entity> found = new ArrayList<>();
                tracker.nearbyEntities(center, range, EntityTracker.Target.ENTITIES, found::add);
                assertEquals(expected, new HashSet<>(found), () -> "range " + range + " around " + center);
                assertEquals(expected.size(), found.size(), () -> "each entity once, range " + range + " around " + center);
            }
            for (int chunkRange = -1; chunkRange <= 10; chunkRange++) {
                final int r = chunkRange;
                final Set<Entity> expected = new HashSet<>();
                positions.forEach((entity, point) -> {
                    if (Math.abs(point.chunkX() - center.chunkX()) <= r && Math.abs(point.chunkZ() - center.chunkZ()) <= r) {
                        expected.add(entity);
                    }
                });
                final List<Entity> found = new ArrayList<>();
                tracker.nearbyEntitiesByChunkRange(center, chunkRange, EntityTracker.Target.ENTITIES, found::add);
                assertEquals(expected, new HashSet<>(found), () -> "chunk range " + r + " around " + center);
                assertEquals(expected.size(), found.size(), () -> "each entity once, chunk range " + r + " around " + center);
            }
        }
    }

    @Test
    public void nearbySingleChunk() {
        var ent1 = new Entity(EntityType.ZOMBIE);
        var ent2 = new Entity(EntityType.ZOMBIE);
        var ent3 = new Entity(EntityType.ZOMBIE);
        var updater = new EntityTracker.Update<>() {
            @Override
            public void add(Entity entity) {
                // Empty
            }

            @Override
            public void remove(Entity entity) {
                // Empty
            }
        };

        EntityTracker tracker = EntityTracker.newTracker(ServerConfig.defaults().entityViewDistance());
        tracker.register(ent1, new Vec(5, 0, 5), EntityTracker.Target.ENTITIES, updater);
        tracker.register(ent2, new Vec(8, 0, 8), EntityTracker.Target.ENTITIES, updater);
        tracker.register(ent3, new Vec(17, 0, 17), EntityTracker.Target.ENTITIES, updater);

        Set<Entity> entities = new HashSet<>();

        entities.add(ent1);
        entities.add(ent2);
        tracker.nearbyEntities(Vec.ZERO, 16, EntityTracker.Target.ENTITIES, entities::add);
        assertEquals(Set.of(ent1, ent2), entities);
        entities.clear();

        entities.add(ent1);
        entities.add(ent2);
        tracker.nearbyEntities(new Vec(8, 0, 8), 5, EntityTracker.Target.ENTITIES, entity -> assertTrue(entities.remove(entity)));
        assertEquals(0, entities.size());

        entities.add(ent2);
        tracker.nearbyEntities(new Vec(8, 0, 8), 1, EntityTracker.Target.ENTITIES, entity -> assertTrue(entities.remove(entity)));
        assertEquals(0, entities.size());
    }

    @Test
    public void collectionView() {
        var ent1 = new Entity(EntityType.ZOMBIE);
        var updater = new EntityTracker.Update<>() {
            @Override
            public void add(Entity entity) {
                assertNotSame(ent1, entity);
                fail("No other entity should be registered yet");
            }

            @Override
            public void remove(Entity entity) {
                assertNotSame(ent1, entity);
                fail("No other entity should be registered yet");
            }
        };

        EntityTracker tracker = EntityTracker.newTracker(ServerConfig.defaults().entityViewDistance());
        var entities = tracker.entities();
        var chunkEntities = tracker.chunkEntities(Vec.ZERO, EntityTracker.Target.ENTITIES);

        assertTrue(entities.isEmpty());
        assertTrue(chunkEntities.isEmpty());
        tracker.register(ent1, Vec.ZERO, EntityTracker.Target.ENTITIES, updater);
        assertEquals(1, entities.size());
        // An empty chunk has no bucket to view, the collection has to be queried again once populated
        assertEquals(1, tracker.chunkEntities(Vec.ZERO, EntityTracker.Target.ENTITIES).size());

        assertThrows(Exception.class, () -> entities.add(new Entity(EntityType.ZOMBIE)));
        assertThrows(Exception.class, () -> chunkEntities.add(new Entity(EntityType.ZOMBIE)));
    }
}
