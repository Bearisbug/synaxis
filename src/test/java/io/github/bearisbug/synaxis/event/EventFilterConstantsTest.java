package io.github.bearisbug.synaxis.event;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.event.trait.EntityEvent;
import io.github.bearisbug.synaxis.event.trait.ItemEvent;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

public class EventFilterConstantsTest {

    @Test
    public void theAllFilterAcceptsEveryEventAndHasNoHandler() {
        assertSame(Event.class, EventFilter.ALL.eventType());
        assertNull(EventFilter.ALL.handlerType());
        assertNull(EventFilter.ALL.getHandler(new EventNodeTest.EventTest()));
    }

    @Test
    public void theItemFilterExposesTheItemStackOfTheEvent() {
        var item = ItemStack.of(Material.DIAMOND);
        assertSame(ItemEvent.class, EventFilter.ITEM.eventType());
        assertSame(ItemStack.class, EventFilter.ITEM.handlerType());
        assertSame(item, EventFilter.ITEM.getHandler(new EventNodeTest.ItemTestEvent(item)));
    }

    @Test
    public void theEntityFilterExposesTheEntityOfTheEvent() {
        var process = ServerProcess.of();
        var entity = new Entity(process, EntityType.ZOMBIE);
        assertSame(EntityEvent.class, EventFilter.ENTITY.eventType());
        assertSame(Entity.class, EventFilter.ENTITY.handlerType());
        assertSame(entity, EventFilter.ENTITY.getHandler(new EventNodeTest.EntityTestEvent(entity)));
    }
}
