package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Connection gate L4 ({@code docs/DESIGN-PROD.md} {@code API-PROD-003}): at most
 * {@code synaxis.max-joins-per-tick} players leave the play queue per tick, the others wait for the
 * next one and nobody is kicked for it.
 */
public class JoinsPerTickTest {

    @Test
    public void twelvePlayersWaitingTogetherJoinFiveFiveAndTwo() {
        assertEquals(List.of(5, 10, 12), onlineAfterEachTick(5, 12, 3));
    }

    @Test
    public void withoutALimitTheWholeQueueJoinsInOneTick() {
        assertEquals(List.of(12), onlineAfterEachTick(0, 12, 1));
    }

    private static List<Integer> onlineAfterEachTick(int maxJoinsPerTick, int players, int ticks) {
        final ServerProcess process = ServerProcess.of(ServerConfig.builder()
                .insideTest(true)
                .maxJoinsPerTick(maxJoinsPerTick)
                .build());
        process.dispatcher().start();
        try {
            final Instance instance = process.instance().createInstanceContainer();
            final ConnectionManager connections = process.connection();
            final List<Player> waiting = new ArrayList<>();
            for (int i = 0; i < players; i++) {
                final Player player = connections.createPlayer(new QuietConnection(process),
                        new GameProfile(UUID.randomUUID(), "joiner" + i));
                assertNotNull(player);
                player.setPendingOptions(instance, false);
                connections.transitionConfigToPlay(player);
                waiting.add(player);
            }
            final List<Integer> online = new ArrayList<>();
            for (int tick = 0; tick < ticks; tick++) {
                connections.updateWaitingPlayers();
                online.add(connections.getOnlinePlayerCount());
            }
            for (Player player : waiting) assertTrue(player.isOnline(), player.getUsername() + " was kicked");
            return online;
        } finally {
            process.stop();
        }
    }

    private static final class QuietConnection extends PlayerConnection {
        QuietConnection(ServerProcess process) {
            super(process);
        }

        @Override
        public void sendPacket(SendablePacket packet) {
            // Nobody is listening
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565);
        }
    }
}
