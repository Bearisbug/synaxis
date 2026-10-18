package io.github.bearisbug.synaxis.event;

import io.github.bearisbug.synaxis.coordinate.BlockFace;
import io.github.bearisbug.synaxis.coordinate.BlockVec;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.entity.PlayerHand;
import io.github.bearisbug.synaxis.event.player.PlayerBlockPlaceEvent;
import io.github.bearisbug.synaxis.event.trait.EntityInstanceEvent;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The instance mapped nodes, which every instance hangs on the root, against instance events of an
 * entity that has no instance.
 */
@EnvTest
public class EventFilterInstanceIntegrationTest {

    record NoInstance(Entity entity) implements EntityInstanceEvent {
        @Override
        public Entity getEntity() {
            return entity;
        }
    }

    @Test
    public void anEntityWithoutInstanceSkipsTheInstanceNodesAndTheDispatchGoesOn(Env env) {
        final List<Throwable> reported = new CopyOnWriteArrayList<>();
        env.process().exception().setExceptionHandler(reported::add);
        final var instance = env.createFlatInstance();
        final var entity = new Entity(env.process(), EntityType.ZOMBIE);
        final AtomicInteger scoped = new AtomicInteger();
        instance.eventNode().addListener(NoInstance.class, _ -> scoped.incrementAndGet());
        // A child of the root runs after the mapped instance nodes
        final AtomicInteger after = new AtomicInteger();
        final var child = EventNode.all("after");
        child.addListener(NoInstance.class, _ -> after.incrementAndGet());
        env.process().eventHandler().addChild(child);

        final var event = new NoInstance(entity);
        assertNull(EventFilter.INSTANCE.getHandler(event));
        env.process().eventHandler().call(event);

        assertEquals(0, scoped.get());
        assertEquals(1, after.get(), "the rest of the dispatch must run");
        assertEquals(List.of(), reported);
        env.process().eventHandler().removeChild(child);
    }

    @Test
    public void anEventCarryingItsOwnInstanceIsRoutedThere(Env env) {
        final var a = env.createFlatInstance();
        final var b = env.createFlatInstance();
        final var player = env.createPlayer(a, new Pos(0, 41, 0));
        final AtomicInteger inA = new AtomicInteger();
        final AtomicInteger inB = new AtomicInteger();
        a.eventNode().addListener(PlayerBlockPlaceEvent.class, _ -> inA.incrementAndGet());
        b.eventNode().addListener(PlayerBlockPlaceEvent.class, _ -> inB.incrementAndGet());

        env.process().eventHandler().call(new PlayerBlockPlaceEvent(player, b, Block.STONE, BlockFace.TOP,
                new BlockVec(0, 41, 0), new BlockVec(0, 0, 0), PlayerHand.MAIN));

        assertEquals(0, inA.get());
        assertEquals(1, inB.get(), "the instance the event carries, not the one the player is in");
    }
}
