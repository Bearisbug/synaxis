package io.github.bearisbug.synaxis.event.trait;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.entity.ItemEntity;
import io.github.bearisbug.synaxis.entity.LivingEntity;
import io.github.bearisbug.synaxis.entity.damage.Damage;
import io.github.bearisbug.synaxis.entity.damage.DamageType;
import io.github.bearisbug.synaxis.event.EventFilter;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.event.entity.EntityDamageEvent;
import io.github.bearisbug.synaxis.event.entity.EntityItemMergeEvent;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
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

    @Test
    public void theDamageAndMergeEventsReportTheirEntity() {
        var living = new LivingEntity(EntityType.ZOMBIE);
        var damage = new EntityDamageEvent(living, new Damage(DamageType.GENERIC, null, null, null, 1f), null);
        assertSame(living, damage.getEntity());

        var source = new ItemEntity(ItemStack.of(Material.DIAMOND));
        var merged = new ItemEntity(ItemStack.of(Material.DIAMOND));
        assertSame(source, new EntityItemMergeEvent(source, merged, ItemStack.of(Material.DIAMOND, 2)).getEntity());
    }
}
