package net.minestom.server.listener;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.network.packet.client.play.ClientPickItemFromBlockPacket;
import net.minestom.server.network.packet.client.play.ClientUpdateSignPacket;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Block positions in these packets are fully client controlled and may point anywhere,
 * including chunks that were never loaded.
 */
@EnvTest
public class UnloadedChunkListenerIntegrationTest {

    private static final Vec FAR_AWAY = new Vec(1_000_000, 40, 1_000_000);

    @Test
    public void editingASignInAnUnloadedChunkIsIgnored(Env env) {
        final Player player = env.createConnection().connect(env.createFlatInstance(), new Pos(0, 40, 0));
        final var packet = new ClientUpdateSignPacket(FAR_AWAY, true, List.of("", "", "", ""));
        assertDoesNotThrow(() -> EditSignListener.listener(packet, player));
    }

    @Test
    public void pickingABlockInAnUnloadedChunkIsIgnored(Env env) {
        final Player player = env.createConnection().connect(env.createFlatInstance(), new Pos(0, 40, 0));
        final var packet = new ClientPickItemFromBlockPacket(FAR_AWAY, false);
        assertDoesNotThrow(() -> PlayerPickListener.playerPickBlockListener(packet, player));
    }
}
