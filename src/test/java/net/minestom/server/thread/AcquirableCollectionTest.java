package net.minestom.server.thread;

import net.minestom.server.Tickable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class AcquirableCollectionTest {
    record World() {
    }

    static final class Element implements Tickable, AcquirableSource<Element> {
        final Acquirable<Element> acquirable = Acquirable.unassigned(this);

        @Override
        public void tick(long time) {
        }

        @Override
        public Acquirable<Element> acquirable() {
            return acquirable;
        }
    }

    @Test
    public void acquireSyncUnassignedElement() {
        // An element that has not been dispatched yet has no assigned thread
        Element element = new Element();
        var collection = new AcquirableCollection<>(List.<Acquirable<Element>>of(element.acquirable()));
        AtomicInteger calls = new AtomicInteger();
        assertDoesNotThrow(() -> collection.acquireSync(_ -> calls.incrementAndGet()));
        assertEquals(1, calls.get());
    }

    @Test
    public void acquireSyncMixedElements() {
        ThreadDispatcher<World, Element> dispatcher = ThreadDispatcher.singleThread();
        World world = new World();
        Element assigned = new Element();
        dispatcher.createPartition(world);
        dispatcher.updateElement(assigned, world);
        dispatcher.start();
        dispatcher.updateAndAwait(0);

        Element unassigned = new Element();
        var collection = new AcquirableCollection<>(
                List.<Acquirable<Element>>of(assigned.acquirable(), unassigned.acquirable()));
        AtomicInteger calls = new AtomicInteger();
        assertDoesNotThrow(() -> collection.acquireSync(_ -> calls.incrementAndGet()));
        assertEquals(2, calls.get());

        dispatcher.shutdown();
    }
}
