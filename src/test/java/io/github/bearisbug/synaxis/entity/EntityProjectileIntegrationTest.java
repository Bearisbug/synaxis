package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.event.entity.EntityShootEvent;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class EntityProjectileIntegrationTest {
    @Test
    public void gravityVelocity(Env env) {
        var instance = env.createFlatInstance();
        var shooter = new EntityCreature(EntityType.SKELETON);
        shooter.setInstance(instance, new Pos(0, 42, 0)).join();
        var projectile = new EntityProjectile(shooter, EntityType.ARROW);
        var from = new Pos(0, 42, 0).add(0,
                shooter.getEyeHeight(), shooter.getPosition().direction().z());
        var target = from.add(0, 0, 10);
        projectile.setInstance(instance, from).join();
        projectile.shoot(target, 1, 0);

        var before = projectile.getPosition(); // at start
        var after = projectile.getPosition(); // now - 1 tick, closest to target
        double smallestDistance = 1e6;
        while (true) {
            final double distance = projectile.getPosition().distanceSquared(target);
            if (distance <= smallestDistance) smallestDistance = distance;
            else break;

            after = projectile.getPosition();
            env.tick();
        }

        // Ensure the position is correct.
        // x doesn't change
        // Big delta because ticks aren't very accurate
        assertEquals(before.x(), after.x());
        assertEquals(target.y(), after.y(), 0.6);
        assertEquals(target.z(), after.z(), 0.6);
    }

    @Test
    public void noGravityVelocity(Env env) {
        var instance = env.createFlatInstance();
        var shooter = new EntityCreature(EntityType.SKELETON);
        shooter.setInstance(instance, new Pos(0, 42, 0)).join();
        var projectile = new EntityProjectile(shooter, EntityType.ARROW);
        var from = new Pos(0, 42, 0).add(0,
                shooter.getEyeHeight(), shooter.getPosition().direction().z());
        var target = from.add(0, 0, 10);
        projectile.setNoGravity(true);
        projectile.setInstance(instance, from).join();
        projectile.shoot(target, 1, 0);

        var before = projectile.getPosition(); // at start
        var after = projectile.getPosition(); // now - 1 tick, closest to target
        double smallestDistance = 1e6;
        while (true) {
            final double distance = projectile.getPosition().distanceSquared(target);
            if (distance <= smallestDistance) smallestDistance = distance;
            else break;

            after = projectile.getPosition();
            env.tick();
        }

        // x and y don't change (no gravity) and z changes by Σz velocity.
        assertEquals(before.x(), after.x());
        assertEquals(before.y(), after.y());
        assertEquals(target.z(), after.z(), 0.05);
    }

    @Test
    public void zeroWidthProjectileTicks(Env env) {
        var instance = env.createFlatInstance();
        // Display entities have a zero wide box
        var projectile = new EntityProjectile(null, EntityType.ITEM_DISPLAY);
        projectile.setNoGravity(true);
        projectile.setInstance(instance, new Pos(0.5, 42, 0.5)).join();
        projectile.setVelocity(new Vec(0, 0, 20));

        assertTimeoutPreemptively(Duration.ofSeconds(5), env::tick);
        assertTrue(projectile.getPosition().z() > 0.5, () -> "position " + projectile.getPosition());
    }

    @Test
    public void projectileWithoutShooterShootsFromItself(Env env) {
        var instance = env.createFlatInstance();
        var projectile = new EntityProjectile(null, EntityType.ARROW);
        projectile.setInstance(instance, new Pos(0, 42, 0)).join();
        var shot = new AtomicReference<EntityShootEvent>();
        env.process().eventHandler().addListener(EntityShootEvent.class, shot::set);

        projectile.shoot(new Vec(0, 42, 10), 1, 0);

        assertSame(projectile, shot.get().getEntity());
        var velocity = projectile.getVelocity();
        assertTrue(Double.isFinite(velocity.x()) && Double.isFinite(velocity.y()) && Double.isFinite(velocity.z()),
                () -> "velocity " + velocity);
        assertTrue(velocity.z() > 0, () -> "velocity " + velocity);
    }

    @Test
    public void shootRefusesWhatItCannotAimWith(Env env) {
        var instance = env.createFlatInstance();
        var shooter = new EntityCreature(EntityType.SKELETON);
        shooter.setInstance(instance, new Pos(0, 42, 0)).join();
        var projectile = new EntityProjectile(shooter, EntityType.ARROW);
        projectile.setInstance(instance, new Pos(0, 42, 0)).join();
        var eyes = new Pos(0, 42 + shooter.getEyeHeight(), 0);

        var noDirection = assertThrows(IllegalArgumentException.class, () -> projectile.shoot(eyes, 1, 0));
        assertTrue(noDirection.getMessage().contains(String.valueOf(eyes.y())), noDirection.getMessage());
        assertThrows(IllegalArgumentException.class, () -> projectile.shoot(new Vec(0, 42, 10), Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> projectile.shoot(new Vec(0, 42, 10), 1, Double.POSITIVE_INFINITY));

        // A listener can change both, they are checked once it is done
        env.process().eventHandler().addListener(EntityShootEvent.class, event -> event.setPower(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> projectile.shoot(new Vec(0, 42, 10), 1, 0));
    }
}
