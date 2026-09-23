package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.packet.server.common.DisconnectPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerSocketConnection;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import net.kyori.adventure.text.TranslatableComponent;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bound of the online mode handover ({@code docs/DESIGN-PROD.md} §11.1): a previous session
 * that never leaves the world does not keep the new login waiting for more than 5 seconds.
 */
@EnvTest
public class SessionHandoverIntegrationTest {

    @Test
    public void aSessionThatNeverLeavesTimesTheNewLoginOutAfterFiveSeconds(Env env) throws Exception {
        final GameProfile profile = new GameProfile(UUID.randomUUID(), "Stuck");
        // The fixture's connection ignores disconnect(): kicked, it keeps its uuid and its entity
        final var previousConnection = env.createConnection(profile);
        final var kicks = previousConnection.trackIncoming(DisconnectPacket.class);
        final Player previous = previousConnection.connect(env.createFlatInstance(), new Pos(0, 42, 0));

        final CompletableFuture<Player> replaced = new CompletableFuture<>();
        final long start = System.nanoTime();
        Thread.startVirtualThread(() -> {
            try (SocketChannel channel = SocketChannel.open()) {
                replaced.complete(env.process().connection().createPlayerReplacing(
                        new PlayerSocketConnection(env.process(), channel, new InetSocketAddress("localhost", 25565),
                                Thread.currentThread(), Thread.currentThread()), profile));
            } catch (Throwable t) {
                replaced.completeExceptionally(t);
            }
        });

        assertNull(replaced.get(10, TimeUnit.SECONDS), "the uuid never came free");
        final long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(waitedMillis >= 5_000 && waitedMillis < 7_000, "waited " + waitedMillis + " ms");
        kicks.assertAnyMatch(kick -> kick.message() instanceof TranslatableComponent reason
                && reason.key().equals("synaxis:logged-in-elsewhere"));
        assertSame(previous, env.process().connection().getOnlinePlayerByUuid(profile.uuid()));
    }
}
