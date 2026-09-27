package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.Auth;
import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.coordinate.ChunkRange;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.network.packet.PacketVanilla;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientSettingsPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginSuccessPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.JoinGamePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.UnloadChunkPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.UpdateViewDistancePacket;
import io.github.bearisbug.synaxis.network.player.ClientSettings;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a client over a real socket is told about its view distance ({@code docs/DESIGN-PROD.md}
 * {@code REQ-PROD-039}, §19「服务端按玩家下调视距」): the instance's in {@code JoinGame}, then the cap
 * of the player in {@code UpdateViewDistance}, ahead of the chunks it unloads.
 */
public class ViewDistanceSocketTest {

    @Test
    public void theClientIsToldTheViewDistanceOfTheInstanceThenTheCap() throws Exception {
        // The facade, not a bare process: only MinecraftServer#start runs the tick
        final MinecraftServer server = MinecraftServer.init(new Auth.Offline());
        final ServerProcess process = server.process();
        final Instance instance = process.instance().createInstanceContainer();
        instance.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.STONE));
        instance.viewDistance(12);
        assertNotEquals(12, process.config().chunkViewDistance(), "the instance must differ from the process");
        process.eventHandler().addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(instance);
            event.getPlayer().setRespawnPoint(new Pos(0.5, 40, 0.5));
        });
        server.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        try (EncryptedLoginClient client = new EncryptedLoginClient(process.server().getPort(), process.registries())) {
            client.handshakeAndStart("Viewer");
            assertInstanceOf(LoginSuccessPacket.class, client.readLoginOutcome());
            client.configure();
            assertEquals(12, client.readPlay(JoinGamePacket.class, _ -> { }).viewDistance(),
                    "JoinGame carries the view distance of the instance");

            client.send(ConnectionState.PLAY, new ClientSettingsPacket(ClientSettings.defaultSettings(16)));
            final Player player = awaitPlayer(process);
            awaitUntil(() -> player.effectiveViewDistance() == 13, "the client view distance 16 under the instance's 12");

            player.setMaxViewDistance(6);
            assertEquals(6, client.readPlay(UpdateViewDistancePacket.class, _ -> { }).viewDistance());

            player.setMaxViewDistance(32);
            final int unload = PacketVanilla.SERVER_PACKET_PARSER.play().packetInfo(UnloadChunkPacket.class).id();
            final AtomicInteger unloads = new AtomicInteger();
            assertEquals(12, client.readPlay(UpdateViewDistancePacket.class, id -> {
                if (id == unload) unloads.incrementAndGet();
            }).viewDistance(), "lifting the cap tells the view distance of the instance again");
            assertEquals(ChunkRange.chunksCount(13) - ChunkRange.chunksCount(7), unloads.get(),
                    "the chunks out of the capped ring are unloaded after the client is told");
        } finally {
            process.stop();
            // The pooled buffers are shared by the whole JVM, and the chunks and registries sent here grow
            // some of them past the default size, which other tests size their packets by
            PacketVanilla.PACKET_POOL.clear();
        }
    }

    private static Player awaitPlayer(ServerProcess process) throws InterruptedException {
        awaitUntil(() -> !process.connection().getOnlinePlayers().isEmpty()
                && process.connection().getOnlinePlayers().iterator().next().getInstance() != null, "the player spawns");
        return process.connection().getOnlinePlayers().iterator().next();
    }

    private static void awaitUntil(BooleanSupplier condition, String what) throws InterruptedException {
        for (int i = 0; i < 400 && !condition.getAsBoolean(); i++) Thread.sleep(25);
        assertTrue(condition.getAsBoolean(), what);
    }
}
