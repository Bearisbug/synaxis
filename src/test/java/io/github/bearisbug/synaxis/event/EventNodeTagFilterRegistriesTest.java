package io.github.bearisbug.synaxis.event;

import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.tag.Tag;
import io.github.bearisbug.synaxis.testing.RegistriesTest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@RegistriesTest
public class EventNodeTagFilterRegistriesTest {

    private static final Tag<Integer> LEVEL = Tag.Integer("level");

    @Test
    public void onlyAHandlerCarryingTheTagPassesTheFilter() {
        AtomicInteger calls = new AtomicInteger();
        var node = EventNode.tag("tagged", EventFilter.ITEM, LEVEL);
        node.addListener(EventNodeTest.ItemTestEvent.class, _ -> calls.incrementAndGet());

        node.call(new EventNodeTest.ItemTestEvent(ItemStack.of(Material.DIAMOND)));
        assertEquals(0, calls.get());

        node.call(new EventNodeTest.ItemTestEvent(ItemStack.of(Material.DIAMOND).withTag(LEVEL, 1)));
        assertEquals(1, calls.get());
    }

    @Test
    public void theTagValueConditionIsCheckedOnTheHandler() {
        AtomicInteger calls = new AtomicInteger();
        var node = EventNode.tag("tagged", EventFilter.ITEM, LEVEL, level -> level != null && level > 2);
        node.addListener(EventNodeTest.ItemTestEvent.class, _ -> calls.incrementAndGet());

        node.call(new EventNodeTest.ItemTestEvent(ItemStack.of(Material.DIAMOND).withTag(LEVEL, 1)));
        assertEquals(0, calls.get());

        node.call(new EventNodeTest.ItemTestEvent(ItemStack.of(Material.DIAMOND).withTag(LEVEL, 3)));
        assertEquals(1, calls.get());
    }

    @Test
    public void theTagConditionAlsoSeesAMissingTagAsNull() {
        AtomicInteger calls = new AtomicInteger();
        var node = EventNode.tag("untagged", EventFilter.ITEM, LEVEL, level -> level == null);
        node.addListener(EventNodeTest.ItemTestEvent.class, _ -> calls.incrementAndGet());

        node.call(new EventNodeTest.ItemTestEvent(ItemStack.of(Material.DIAMOND)));
        assertEquals(1, calls.get(), "A missing tag has to reach the condition as null");

        node.call(new EventNodeTest.ItemTestEvent(ItemStack.of(Material.DIAMOND).withTag(LEVEL, 1)));
        assertEquals(1, calls.get());
    }
}
