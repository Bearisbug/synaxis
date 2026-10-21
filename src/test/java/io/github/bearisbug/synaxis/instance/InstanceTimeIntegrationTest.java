package io.github.bearisbug.synaxis.instance;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import io.github.bearisbug.synaxis.registry.BuiltinRegistries;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.world.DimensionType;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class InstanceTimeIntegrationTest {

    @Test
    void overworldTicking(Env env) {
        var instance = env.createEmptyInstance();
        var clock = instance.defaultClock();
        assertNotNull(clock);

        assertEquals(0, clock.time());

        for (int i = 0; i < 100; i++) {
            env.tick();
            assertEquals(i + 1, clock.time());
        }
    }

    @Test
    void pausing(Env env) {
        var instance = env.createEmptyInstance();
        var clock = instance.defaultClock();
        assertNotNull(clock);

        assertEquals(0, clock.time());
        for (int i = 0; i < 5; i++) env.tick();
        assertEquals(5, clock.time());

        clock.pause();
        assertTrue(clock.paused());

        for (int i = 0; i < 5; i++) env.tick();
        assertEquals(5, clock.time());
    }

    @Test
    void partialTickRate(Env env) {
        var instance = env.createEmptyInstance();
        var clock = instance.defaultClock();
        assertNotNull(clock);

        clock.rate(0.2f);
        for (int i = 0; i < 10; i++) env.tick();
        assertEquals(2, clock.time());
    }

    @Test
    void multipleClocks(Env env) {
        var myOtherClock = env.process().opaque(BuiltinRegistries.WORLD_CLOCK).register(Key.key("synaxis:clock"), CompoundBinaryTag.empty());

        var instance = env.createEmptyInstance();
        var defaultClock = Objects.requireNonNull(instance.defaultClock());
        var otherClock = instance.clock(myOtherClock);

        for (int i = 0; i < 5; i++) env.tick();
        defaultClock.pause();
        for (int i = 0; i < 5; i++) env.tick();

        assertEquals(5, defaultClock.time());
        assertEquals(10, otherClock.time());
    }

    // The vanilla nether has no default clock: setTime(long) has no time to set
    @Test
    void setTimeWithoutADefaultClockThrows(Env env) {
        var instance = env.process().instance().createInstanceContainer(DimensionType.THE_NETHER);
        assertNull(instance.defaultClock());
        assertEquals(-1, instance.getTime());

        var thrown = assertThrows(IllegalStateException.class, () -> instance.setTime(6000));
        assertTrue(thrown.getMessage().contains("minecraft:the_nether"), thrown.getMessage());

        var overworld = env.createEmptyInstance();
        overworld.setTime(6000);
        assertEquals(6000, overworld.getTime(), "the overworld has its default clock");
    }

    // NaN or infinity would stay in the partial tick for good, a rate set again would not bring the clock back
    @Test
    void aRateThatIsNotAFiniteNumberIsRefused(Env env) {
        var clock = Objects.requireNonNull(env.createEmptyInstance().defaultClock());
        assertThrows(IllegalArgumentException.class, () -> clock.rate(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> clock.rate(Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> clock.rate(-1));
        assertEquals(1f, clock.rate());
        env.tick();
        assertEquals(1, clock.time());
    }

    // Above 2^31 ticks per tick a finite rate grew the partial tick to infinity, and a rate set again did not bring
    // the clock back; MAX_RATE keeps well below that
    @Test
    void aRateAboveTheMaximumIsRefused(Env env) {
        var clock = Objects.requireNonNull(env.createEmptyInstance().defaultClock());
        assertThrows(IllegalArgumentException.class, () -> clock.rate(Float.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> clock.rate(Math.nextUp(Clock.MAX_RATE)));
        clock.rate(Clock.MAX_RATE);
        env.tick();
        env.tick();
        assertEquals(2L << 24, clock.time());
        clock.rate(1);
        env.tick();
        assertEquals((2L << 24) + 1, clock.time());
    }
}
