package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.packet.server.play.AttachEntityPacket;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@EnvTest
public class EntityLeashIntegrationTest {

    /**
     * A removed entity lets go of its leash: its holder no longer lists it, and the viewers are told it is unleashed
     * before it is destroyed.
     */
    @Test
    public void aRemovedEntityLeavesItsLeashHolder(Env env) {
        final Instance instance = env.createFlatInstance();
        final TestConnection connection = env.createConnection();
        connection.connect(instance, new Pos(0.5, 42, 0.5));
        final Entity holder = new Entity(EntityType.ZOMBIE);
        holder.setInstance(instance, new Pos(2.5, 42, 0.5)).join();
        final Entity leashed = new Entity(EntityType.COW);
        leashed.setInstance(instance, new Pos(4.5, 42, 0.5)).join();
        leashed.setLeashHolder(holder);
        env.tick();
        final Collector<AttachEntityPacket> attaches = connection.trackIncoming(AttachEntityPacket.class);

        // Ticked already, so it belongs to a tick thread and the test thread has to acquire it
        leashed.acquirable().sync(entity -> entity.remove());

        assertEquals(Set.of(), holder.getLeashedEntities());
        assertNull(leashed.getLeashHolder());
        attaches.assertSingle(packet -> assertEquals(new AttachEntityPacket(leashed.getEntityId(), -1), packet));
    }
}
