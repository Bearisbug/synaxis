package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.Auth;
import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.listener.preplay.LoginListener;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginAcknowledgedPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.DisconnectPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A connection that got its {@code LoginSuccess} before the stop and acknowledges it only after the
 * kicking phase ran ({@code docs/DESIGN-PROD.md} §11.4 {@code kicking → plugins_disabled}, §16):
 * {@code ConnectionManager#shutdown} kicked every player it held, and this one did not exist yet.
 * It is refused its player with {@code synaxis:server-stopping}; it used to be created and walk into
 * a configuration whose plugins were already disabled.
 */
@EnvTest
public class LoginAfterConnectionShutdownIntegrationTest {

    @Test
    public void anOfflineLoginAcknowledgedAfterTheKickingIsToldTheServerStops(Env env) throws IOException {
        final ConnectionManager manager = env.process().connection();
        manager.shutdown();
        assertTrue(manager.isShutDown());

        try (DuplicateUuidLoginIntegrationTest.KickCapturingConnection late =
                     new DuplicateUuidLoginIntegrationTest.KickCapturingConnection(env.process())) {
            late.UNSAFE_setProfile(new GameProfile(UUID.randomUUID(), "late0000"));
            late.setServerState(ConnectionState.CONFIGURATION);
            LoginListener.loginAckListener(new ClientLoginAcknowledgedPacket(), late);

            assertEquals("synaxis:server-stopping", late.kickKey(), "refused for the stop, not for a live reason");
            assertInstanceOf(DisconnectPacket.class, late.kickPacket());
            assertNull(manager.getPlayer(late), "no player was made for it");
        }
        assertEquals(List.of(), List.copyOf(manager.getConfigPlayers()), "nobody went into the configuration");
    }

    @Test
    public void anOnlineLoginAcknowledgedAfterTheKickingIsToldTheServerStopsWithoutWaiting() throws IOException {
        final ServerProcess process = ServerProcess.of(new Auth.Online(),
                ServerConfig.builder().insideTest(true).shutdownOnSignal(false).build());
        process.connection().shutdown();

        try (DuplicateUuidLoginIntegrationTest.KickCapturingConnection late =
                     new DuplicateUuidLoginIntegrationTest.KickCapturingConnection(process)) {
            late.UNSAFE_setProfile(new GameProfile(UUID.randomUUID(), "late0001"));
            late.setServerState(ConnectionState.CONFIGURATION);
            final long start = System.nanoTime();
            LoginListener.loginAckListener(new ClientLoginAcknowledgedPacket(), late);
            final long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertEquals("synaxis:server-stopping", late.kickKey(), "refused for the stop, not state-unavailable");
            assertTrue(tookMillis < 1_000, "refused at once, not after the 5 s handover wait: " + tookMillis + " ms");
            assertNull(process.connection().getPlayer(late));
        }
    }
}
