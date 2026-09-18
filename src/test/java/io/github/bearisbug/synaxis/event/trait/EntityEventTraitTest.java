package io.github.bearisbug.synaxis.event.trait;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.event.EventFilter;
import io.github.bearisbug.synaxis.event.EventNode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class EntityEventTraitTest {

    record InstanceScoped(Entity entity) implements EntityInstanceEvent {
        @Override
        public Entity getEntity() {
            return entity;
        }
    }

    @Test
    public void anEntityEventReportsTheEntityItWasBuiltWith() {
        var entity = new Entity(ServerProcess.of(), EntityType.ZOMBIE);
        assertSame(entity, new InstanceScoped(entity).getEntity());
    }

    @Test
    public void anEntityWithoutInstanceTripsTheInstanceScopedContract() {
        // EntityInstanceEvent documents that the entity's instance must be non-null; the tests run
        // with assertions enabled so a spawn-less entity has to fail loudly here
        var entity = new Entity(ServerProcess.of(), EntityType.ZOMBIE);
        var event = new InstanceScoped(entity);
        assertThrows(AssertionError.class, event::getInstance);
    }

    @Test
    public void theEntityFilterRoutesAnInstanceScopedEventOnItsEntity() {
        var process = ServerProcess.of();
        var entity = new Entity(process, EntityType.ZOMBIE);
        var other = new Entity(process, EntityType.ZOMBIE);
        var node = EventNode.all("root");
        AtomicInteger calls = new AtomicInteger();
        var mapped = node.map(entity, EventFilter.ENTITY);
        mapped.addListener(InstanceScoped.class, _ -> calls.incrementAndGet());

        node.call(new InstanceScoped(other));
        assertEquals(0, calls.get());

        node.call(new InstanceScoped(entity));
        assertEquals(1, calls.get());
    }
}
