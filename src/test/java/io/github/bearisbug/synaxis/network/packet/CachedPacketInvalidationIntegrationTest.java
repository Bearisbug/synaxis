package io.github.bearisbug.synaxis.network.packet;

import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.server.CachedPacket;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.PluginMessagePacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A chunk invalidates its cached data packet while another thread may already be framing the
 * previous state, so an invalidation that lands during a computation must not be overwritten by
 * the publication of that computation.
 */
@EnvTest
public class CachedPacketInvalidationIntegrationTest {

    @Test
    public void anInvalidationDuringComputationIsNotOverwritten(Env env) {
        assertNotNull(env.process().registries());
        final AtomicInteger supplied = new AtomicInteger();
        final AtomicReference<CachedPacket> holder = new AtomicReference<>();
        final CachedPacket cached = new CachedPacket(() -> {
            final int round = supplied.incrementAndGet();
            // Stands for the writer that changes the source right after the snapshot was taken
            if (round == 1) holder.get().invalidate();
            return (ServerPacket) new PluginMessagePacket("ch", new byte[]{(byte) round});
        });
        holder.set(cached);

        assertNotNull(cached.body(ConnectionState.PLAY, env.process().registries(), 0));

        assertFalse(cached.isValid(), "an invalidation during the computation must not be lost");
        assertNotNull(cached.body(ConnectionState.PLAY, env.process().registries(), 0));
        assertEquals(2, supplied.get(), "the invalidated frame must be recomputed on the next read");
    }
}
