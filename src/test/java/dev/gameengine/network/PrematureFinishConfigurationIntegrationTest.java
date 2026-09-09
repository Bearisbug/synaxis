package dev.gameengine.network;

import net.kyori.adventure.text.TranslatableComponent;
import dev.gameengine.coordinate.Pos;
import dev.gameengine.entity.Player;
import dev.gameengine.event.instance.InstanceTickEvent;
import dev.gameengine.listener.preplay.LoginListener;
import dev.gameengine.network.packet.client.configuration.ClientFinishConfigurationPacket;
import dev.gameengine.network.packet.server.common.DisconnectPacket;
import dev.gameengine.testing.Env;
import dev.gameengine.testing.EnvTest;
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
        final var connection = env.createConnection();
        final Player player = connection.connect(env.createFlatInstance(), new Pos(0, 40, 0));
        final var disconnects = connection.trackIncoming(DisconnectPacket.class);
        // The server only accepts the acknowledgement once it wrote FinishConfigurationPacket
        player.getPlayerConnection().setServerState(ConnectionState.CONFIGURATION);

        LoginListener.finishConfigListener(new ClientFinishConfigurationPacket(), player);

        assertFalse(player.getPlayerConnection().isOnline(), "the connection must be dropped");
        disconnects.assertSingle(disconnect -> assertEquals("engine:protocol-error",
                assertInstanceOf(TranslatableComponent.class, disconnect.message()).key()));
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
