package net.minestom.server.network;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.instance.InstanceTickEvent;
import net.minestom.server.listener.preplay.LoginListener;
import net.minestom.server.network.packet.client.configuration.ClientFinishConfigurationPacket;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * A client acknowledging a configuration phase the server never finished must not
 * be able to abort a server tick.
 */
@EnvTest
public class PrematureFinishConfigurationIntegrationTest {

    @Test
    public void finishConfigurationBeforeTheServerIsReadyIsRejected(Env env) {
        final Player player = env.createConnection().connect(env.createFlatInstance(), new Pos(0, 40, 0));
        // The server only accepts the acknowledgement once it wrote FinishConfigurationPacket
        player.getPlayerConnection().setServerState(ConnectionState.CONFIGURATION);

        LoginListener.finishConfigListener(new ClientFinishConfigurationPacket(), player);

        assertFalse(player.getPlayerConnection().isOnline(), "the connection must be dropped");
        env.process().connection().updateWaitingPlayers();
    }

    @Test
    public void aTickSurvivesAnUnexpectedConfigurationTransition(Env env) {
        final var instance = env.createFlatInstance();
        final Player player = env.createConnection().connect(instance, new Pos(0, 40, 0));

        final AtomicReference<Throwable> caught = new AtomicReference<>();
        env.process().exception().setExceptionHandler(caught::set);
        final AtomicInteger instanceTicks = new AtomicInteger();
        env.process().eventHandler().addListener(InstanceTickEvent.class, _ -> instanceTicks.incrementAndGet());

        // pendingInstance was consumed by the first UNSAFE_init, queueing the player again
        // reproduces the state a premature acknowledgement used to leave the server in.
        env.process().connection().transitionConfigToPlay(player);

        assertDoesNotThrow(env::tick);
        assertInstanceOf(IllegalStateException.class, caught.get(), "the failure must be reported, not swallowed");
        assertEquals(1, instanceTicks.get(), "the rest of the tick must still run");
    }
}
