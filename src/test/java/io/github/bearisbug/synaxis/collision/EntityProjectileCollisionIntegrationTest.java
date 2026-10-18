package io.github.bearisbug.synaxis.collision;

import io.github.bearisbug.synaxis.JvmFlags;
import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityProjectile;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.entity.LivingEntity;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.event.entity.projectile.ProjectileCollideWithBlockEvent;
import io.github.bearisbug.synaxis.event.entity.projectile.ProjectileCollideWithEntityEvent;
import io.github.bearisbug.synaxis.event.entity.projectile.ProjectileUncollideEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.WorldBorder;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityVelocityPacket;
import io.github.bearisbug.synaxis.timer.TimeUnit;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class EntityProjectileCollisionIntegrationTest {

    @Test
    public void blockShootAndBlockRemoval(Env env) {
        final Instance instance = env.createFlatInstance();
        instance.setWorldBorder(WorldBorder.defaultBorder().withDiameter(1000));

        final Entity shooter = new Entity(EntityType.SKELETON);
        shooter.setInstance(instance, new Pos(0, 40, 0)).join();

        final EntityProjectile projectile = new EntityProjectile(shooter, EntityType.ARROW);
        projectile.setInstance(instance, shooter.getPosition().withY(y -> y + shooter.getEyeHeight())).join();

        final Point blockPosition = new Vec(5, 40, 0);
        final Block block = Block.GRASS_BLOCK;
        instance.setBlock(blockPosition, block);
        projectile.shoot(blockPosition, 1, 0);

        final var eventRef = new AtomicReference<ProjectileCollideWithBlockEvent>();
        env.process().eventHandler().addListener(ProjectileCollideWithBlockEvent.class, eventRef::set);

        final long tick = TimeUnit.SERVER_TICK.getDuration().toMillis();
        for (int i = 0; i < JvmFlags.SERVER_TICKS_PER_SECOND; ++i) {
            projectile.tick(i * tick);
        }

        var event = eventRef.get();
        assertNotNull(event);
        assertEquals(blockPosition, new Vec(event.getCollisionPosition().blockX(), event.getCollisionPosition().blockY(), event.getCollisionPosition().blockZ()));
        assertEquals(block, event.getBlock());

        final var eventRef2 = new AtomicReference<ProjectileUncollideEvent>();
        env.process().eventHandler().addListener(ProjectileUncollideEvent.class, eventRef2::set);
        eventRef.set(null);
        instance.setBlock(blockPosition, Block.AIR);

        for (int i = 0; i < JvmFlags.SERVER_TICKS_PER_SECOND; ++i) {
            projectile.tick((JvmFlags.SERVER_TICKS_PER_SECOND + i) * tick);
        }
        event = eventRef.get();
        final var event2 = eventRef2.get();
        assertNotNull(event);
        assertNotNull(event2);
        assertEquals(blockPosition.withY(y -> y - 1), new Vec(event.getCollisionPosition().blockX(), event.getCollisionPosition().blockY(), event.getCollisionPosition().blockZ()));
    }

    @Test
    public void entityShoot(Env env) {
        final Instance instance = env.createFlatInstance();
        instance.setWorldBorder(WorldBorder.defaultBorder().withDiameter(1000));

        final Entity shooter = new Entity(EntityType.SKELETON);
        shooter.setInstance(instance, new Pos(0, 40, 0)).join();

        for (double dx = 1; dx <= 3; dx += .2) {
            singleEntityShoot(env, instance, shooter, new Vec(dx, 40, 0));
        }
    }

    private static void singleEntityShoot(
            Env env,
            Instance instance,
            Entity shooter,
            final Point targetPosition
    ) {
        final EntityProjectile projectile = new EntityProjectile(shooter, EntityType.ARROW);
        projectile.setInstance(instance, shooter.getPosition().withY(y -> y + shooter.getEyeHeight())).join();

        final LivingEntity target = new LivingEntity(EntityType.RABBIT);
        target.setInstance(instance, targetPosition.asPos()).join();
        projectile.shoot(targetPosition, 1, 0);

        final var eventRef = new AtomicReference<ProjectileCollideWithEntityEvent>();
        final var eventNode = EventNode.all("projectile-test");
        eventNode.addListener(ProjectileCollideWithEntityEvent.class, event -> {
            event.getEntity().remove();
            eventRef.set(event);
            env.process().eventHandler().removeChild(eventNode);
        });
        env.process().eventHandler().addChild(eventNode);

        final long tick = TimeUnit.SERVER_TICK.getDuration().toMillis();
        for (int i = 0; i < JvmFlags.SERVER_TICKS_PER_SECOND; ++i) {
            if (!projectile.isRemoved()) {
                projectile.tick(i * tick);
            }
        }

        final var event = eventRef.get();
        assertNotNull(event, "Could not hit entity at " + targetPosition);
        assertSame(target, event.getTarget());
        assertTrue(projectile.getBoundingBox().intersectEntity(event.getCollisionPosition(), target));
        target.remove();
    }

    @Test
    public void entitySelfShoot(Env env) {
        final Instance instance = env.createFlatInstance();
        instance.setWorldBorder(WorldBorder.defaultBorder().withDiameter(1000));

        final LivingEntity shooter = new LivingEntity(EntityType.SKELETON);
        shooter.setInstance(instance, new Pos(0, 40, 0)).join();

        final EntityProjectile projectile = new EntityProjectile(shooter, EntityType.ARROW);
        projectile.setInstance(instance, shooter.getPosition().withY(y -> y + shooter.getEyeHeight())).join();

        projectile.shoot(new Vec(0, 60, 0), 1, 0);

        final var eventRef = new AtomicReference<ProjectileCollideWithEntityEvent>();
        env.process().eventHandler().addListener(ProjectileCollideWithEntityEvent.class, event -> {
            event.getEntity().remove();
            eventRef.set(event);
        });

        final long tick = TimeUnit.SERVER_TICK.getDuration().toMillis();
        for (int i = 0; i < JvmFlags.SERVER_TICKS_PER_SECOND * 5; ++i) {
            if (!projectile.isRemoved()) {
                projectile.tick(i * tick);
            }
        }

        final var event = eventRef.get();
        assertNotNull(event);
        assertSame(shooter, event.getTarget());
        assertTrue(shooter.getBoundingBox().intersectEntity(shooter.getPosition(), projectile));
    }

    @Test
    public void stuckProjectileStopsOnlyOnce(Env env) {
        final Instance instance = env.createFlatInstance();
        instance.setBlock(3, 41, 0, Block.STONE);
        final var connection = env.createConnection();
        var _ = connection.connect(instance, new Pos(0.5, 40, 4));

        final EntityProjectile projectile = new EntityProjectile(null, EntityType.ARROW);
        projectile.setNoGravity(true);
        projectile.setInstance(instance, new Pos(0.5, 41.2, 0.5)).join();
        projectile.setVelocity(new Vec(20, 0, 0));

        final long tick = TimeUnit.SERVER_TICK.getDuration().toMillis();
        int ticks = 0;
        while (!projectile.isOnGround() && ticks < JvmFlags.SERVER_TICKS_PER_SECOND) {
            projectile.tick(ticks * tick);
            ticks++;
        }
        assertTrue(projectile.isOnGround(), "the arrow got stuck in the stone");

        final var velocities = connection.trackIncoming(EntityVelocityPacket.class);
        for (int i = 0; i < 20; ++i) {
            projectile.tick(ticks * tick);
            ticks++;
            assertTrue(projectile.isOnGround(), "isOnGround() reports a stuck projectile between ticks");
        }
        final long sent = velocities.collect().stream().filter(packet -> packet.entityId() == projectile.getEntityId()).count();
        assertTrue(sent <= 2, () -> sent + " velocity packets while stuck");
        assertTrue(projectile.hasNoGravity());

        final var uncollided = new AtomicReference<ProjectileUncollideEvent>();
        env.process().eventHandler().addListener(ProjectileUncollideEvent.class, uncollided::set);
        instance.setBlock(3, 41, 0, Block.AIR);
        projectile.tick(ticks * tick);
        assertNotNull(uncollided.get());
        assertFalse(projectile.isOnGround());
        assertFalse(projectile.hasNoGravity());
    }

    @Test
    public void entityHitInTheChunkTheProjectileLeft(Env env) {
        final Instance instance = env.createFlatInstance();
        instance.loadChunk(1, 0).join();
        final LivingEntity target = new LivingEntity(EntityType.RABBIT);
        target.setNoGravity(true);
        target.setInstance(instance, new Pos(15.6, 41, 0.5)).join();

        // Two blocks a tick, from chunk 0 into chunk 1
        final EntityProjectile projectile = new EntityProjectile(null, EntityType.ARROW);
        projectile.setNoGravity(true);
        projectile.setInstance(instance, new Pos(15, 41, 0.5)).join();
        projectile.setVelocity(new Vec(40, 0, 0));

        final var hit = new AtomicReference<ProjectileCollideWithEntityEvent>();
        env.process().eventHandler().addListener(ProjectileCollideWithEntityEvent.class, hit::set);
        projectile.tick(0);

        assertNotNull(hit.get(), "the rabbit in chunk 0 was missed");
        assertSame(target, hit.get().getTarget());
    }

    @Test
    public void cancelledHitIsDispatchedOncePerTick(Env env) {
        final Instance instance = env.createFlatInstance();
        final LivingEntity target = new LivingEntity(EntityType.RABBIT);
        target.setNoGravity(true);
        target.setInstance(instance, new Pos(1.5, 41, 0.5)).join();

        // Three of the eight sampled points of this tick overlap the rabbit
        final EntityProjectile projectile = new EntityProjectile(null, EntityType.ARROW);
        projectile.setNoGravity(true);
        projectile.setInstance(instance, new Pos(0.5, 41, 0.5)).join();
        projectile.setVelocity(new Vec(40, 0, 0));

        final AtomicInteger hits = new AtomicInteger();
        env.process().eventHandler().addListener(ProjectileCollideWithEntityEvent.class, event -> {
            hits.incrementAndGet();
            event.setCancelled(true);
        });
        projectile.tick(0);

        assertEquals(1, hits.get());
        assertFalse(projectile.isRemoved());
        assertTrue(projectile.getPosition().x() > 2, () -> "position " + projectile.getPosition());
    }

}
