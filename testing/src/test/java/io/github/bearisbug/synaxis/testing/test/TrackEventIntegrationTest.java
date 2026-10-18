package io.github.bearisbug.synaxis.testing.test;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.event.EventFilter;
import io.github.bearisbug.synaxis.event.trait.EntityEvent;
import io.github.bearisbug.synaxis.event.trait.InstanceEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link Env#trackEvent} listens on the node of the actor, which for an entity or an instance is the
 * node the actor itself listens on: collecting takes the tracker's own listener off that node and
 * leaves the node, and whatever else listens on it, alone.
 */
@EnvTest
public class TrackEventIntegrationTest {

    private record EntityPoke(Entity entity) implements EntityEvent {
        @Override
        public Entity getEntity() {
            return entity;
        }
    }

    private record InstancePoke(Instance instance) implements InstanceEvent {
        @Override
        public Instance getInstance() {
            return instance;
        }
    }

    @Test
    void collectingOneTrackerLeavesTheOtherOnTheSameEntity(Env env) {
        var player = env.createPlayer(env.createFlatInstance(), new Pos(0, 42, 0));
        var first = env.trackEvent(EntityPoke.class, EventFilter.ENTITY, player);
        var second = env.trackEvent(EntityPoke.class, EventFilter.ENTITY, player);

        env.process().eventHandler().call(new EntityPoke(player));
        first.assertSingle();
        env.process().eventHandler().call(new EntityPoke(player));

        second.assertCount(2);
        first.assertSingle();
    }

    @Test
    void collectingATrackerOnAnInstanceLeavesTheInstancesOwnListeners(Env env) {
        var instance = env.createFlatInstance();
        var heard = new AtomicInteger();
        instance.eventNode().addListener(InstancePoke.class, _ -> heard.incrementAndGet());
        var tracker = env.trackEvent(InstancePoke.class, EventFilter.INSTANCE, instance);

        env.process().eventHandler().call(new InstancePoke(instance));
        tracker.assertSingle();
        env.process().eventHandler().call(new InstancePoke(instance));

        assertEquals(2, heard.get(), "the instance's own listener still hears its events");
        tracker.assertSingle();
    }
}
