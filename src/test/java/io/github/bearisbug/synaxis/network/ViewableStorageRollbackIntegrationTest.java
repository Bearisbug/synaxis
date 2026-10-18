package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.Viewable;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SystemChatPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The buffer of a viewable storage is shared by every packet sent to that viewable and is copied
 * as a whole to its viewers, so a packet failing to serialize must not leave a truncated frame
 * behind.
 * <p>
 * The fixture process disables viewable packets, so the test drives its own instance with them on.
 */
@EnvTest
public class ViewableStorageRollbackIntegrationTest {

    @Test
    public void aPacketFailingToSerializeLeavesNothingInTheSharedBuffer(Env env) {
        assertNotNull(env.process().registries());
        final PacketViewableUtils viewablePackets = new PacketViewableUtils(env.process(), true,
                env.process().config().pooledBufferSize());
        // NBT components are not implemented, the component writer throws once the frame is started
        final ServerPacket unwritable = new SystemChatPacket(
                Component.storageNBT("path", Key.key("synaxis:storage")), false);
        final CountingViewable viewable = new CountingViewable();

        assertThrows(IllegalArgumentException.class,
                () -> viewablePackets.prepareViewablePacket(viewable, unwritable));
        viewablePackets.flush();

        assertEquals(0, viewable.flushed.get(),
                "a packet that failed to serialize must leave the shared buffer empty");
    }

    private static final class CountingViewable implements Viewable {
        private final AtomicInteger flushed = new AtomicInteger();

        @Override
        public boolean addViewer(Player player) {
            return false;
        }

        @Override
        public boolean removeViewer(Player player) {
            return false;
        }

        @Override
        public Set<? extends Player> getViewers() {
            flushed.incrementAndGet();
            return Set.of();
        }
    }
}
