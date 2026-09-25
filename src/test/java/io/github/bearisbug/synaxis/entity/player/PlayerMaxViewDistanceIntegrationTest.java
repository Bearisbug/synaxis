package io.github.bearisbug.synaxis.entity.player;

import io.github.bearisbug.synaxis.coordinate.ChunkRange;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientSettingsPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.JoinGamePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.UnloadChunkPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.UpdateViewDistancePacket;
import io.github.bearisbug.synaxis.network.player.ClientSettings;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The server side view distance cap of a player and the view distance the client is told
 * ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-039}, {@code API-PROD-019}).
 */
@EnvTest
public class PlayerMaxViewDistanceIntegrationTest {

    private static final Pos SPAWN = new Pos(0.5, 42, 0.5);

    @Test
    public void joinGameCarriesTheViewDistanceOfTheInstance(Env env) {
        final Instance instance = env.createFlatInstance();
        instance.viewDistance(12);
        assertNotEquals(12, env.process().config().chunkViewDistance(), "the instance must differ from the process");
        final TestConnection connection = env.createConnection();
        final Collector<JoinGamePacket> joins = connection.trackIncoming(JoinGamePacket.class);

        connection.connect(instance, SPAWN);

        assertEquals(12, joins.collect().getFirst().viewDistance(), "JoinGame must carry the view distance of the instance");
    }

    @Test
    public void aCapSetBeforeSpawnIsInJoinGame(Env env) {
        final Instance instance = env.createFlatInstance();
        instance.viewDistance(12);
        env.process().eventHandler().addListener(AsyncPlayerConfigurationEvent.class,
                event -> event.getPlayer().setMaxViewDistance(6));
        final TestConnection connection = env.createConnection();
        final Collector<JoinGamePacket> joins = connection.trackIncoming(JoinGamePacket.class);
        final Collector<UpdateViewDistancePacket> updates = connection.trackIncoming(UpdateViewDistancePacket.class);

        final Player player = connection.connect(instance, SPAWN);

        assertEquals(6, joins.collect().getFirst().viewDistance(), "JoinGame must carry the cap of the player");
        assertEquals(List.of(), updates.collect(), "the client already knows it from JoinGame");
        assertEquals(7, player.effectiveViewDistance(), "the first ring is built under the cap");
    }

    /** The §19 scenario: instance 12, client 16, capped to 6. */
    @Test
    public void aCapShrinksTheRingAndTellsTheClient(Env env) {
        final Instance instance = env.createFlatInstance();
        instance.viewDistance(12);
        final TestConnection connection = env.createConnection();
        final Player player = connection.connect(instance, SPAWN);
        player.addPacketToQueue(new ClientSettingsPacket(ClientSettings.defaultSettings(16)));
        env.tick();
        assertEquals(13, player.effectiveViewDistance());

        Collector<UpdateViewDistancePacket> updates = connection.trackIncoming(UpdateViewDistancePacket.class);
        final Collector<UnloadChunkPacket> unloads = connection.trackIncoming(UnloadChunkPacket.class);
        player.setMaxViewDistance(6);
        assertEquals(6, player.getMaxViewDistance());
        assertEquals(13, player.effectiveViewDistance(), "the ring only changes on the player's tick");
        env.tick();

        assertEquals(List.of(new UpdateViewDistancePacket(6)), updates.collect());
        assertEquals(7, player.effectiveViewDistance());
        assertEquals(ChunkRange.chunksCount(13) - ChunkRange.chunksCount(7), unloads.collect().size(),
                "every chunk out of the capped ring is unloaded");

        // 32 lifts the cap: back to the view distance of the instance
        updates = connection.trackIncoming(UpdateViewDistancePacket.class);
        player.setMaxViewDistance(32);
        env.tick();
        assertEquals(List.of(new UpdateViewDistancePacket(12)), updates.collect());
        assertEquals(13, player.effectiveViewDistance());
    }

    @Test
    public void onlyTheLastCapOfATickCounts(Env env) {
        final Instance instance = env.createFlatInstance();
        instance.viewDistance(12);
        final TestConnection connection = env.createConnection();
        final Player player = connection.connect(instance, SPAWN);
        env.tick();

        final Collector<UpdateViewDistancePacket> updates = connection.trackIncoming(UpdateViewDistancePacket.class);
        final Collector<UnloadChunkPacket> unloads = connection.trackIncoming(UnloadChunkPacket.class);
        player.setMaxViewDistance(3);
        player.setMaxViewDistance(6);
        env.tick();

        assertEquals(List.of(new UpdateViewDistancePacket(6)), updates.collect());
        assertEquals(ChunkRange.chunksCount(9) - ChunkRange.chunksCount(7), unloads.collect().size(),
                "the client view distance 8 was capped to 6 once");
    }

    @Test
    public void aCapCanBeSetFromAnotherThread(Env env) {
        final Instance instance = env.createFlatInstance();
        final TestConnection connection = env.createConnection();
        final Player player = connection.connect(instance, SPAWN);
        env.tick();

        final Collector<UpdateViewDistancePacket> updates = connection.trackIncoming(UpdateViewDistancePacket.class);
        CompletableFuture.runAsync(() -> player.setMaxViewDistance(4), Thread::startVirtualThread).join();
        env.tick();

        assertEquals(List.of(new UpdateViewDistancePacket(4)), updates.collect());
        assertEquals(5, player.effectiveViewDistance());
    }

    @Test
    public void theInstanceViewDistanceIsToldUnderTheCap(Env env) {
        final Instance instance = env.createFlatInstance();
        instance.viewDistance(12);
        final TestConnection connection = env.createConnection();
        final Player player = connection.connect(instance, SPAWN);
        env.tick();

        Collector<UpdateViewDistancePacket> updates = connection.trackIncoming(UpdateViewDistancePacket.class);
        instance.viewDistance(10);
        assertEquals(List.of(new UpdateViewDistancePacket(10)), updates.collect());

        player.setMaxViewDistance(6);
        env.tick();
        updates = connection.trackIncoming(UpdateViewDistancePacket.class);
        instance.viewDistance(8);
        assertEquals(List.of(), updates.collect(), "the cap still bounds it at 6");

        updates = connection.trackIncoming(UpdateViewDistancePacket.class);
        instance.viewDistance(4);
        assertEquals(List.of(new UpdateViewDistancePacket(4)), updates.collect());
        assertEquals(5, player.effectiveViewDistance());
    }

    @Test
    public void anInstanceWithAnotherViewDistanceIsTold(Env env) {
        final Instance near = env.createFlatInstance();
        near.viewDistance(4);
        final Instance far = env.createFlatInstance();
        far.viewDistance(12);
        final TestConnection connection = env.createConnection();
        final Player player = connection.connect(far, SPAWN);

        Collector<UpdateViewDistancePacket> updates = connection.trackIncoming(UpdateViewDistancePacket.class);
        player.setInstance(near, SPAWN).join();
        assertEquals(List.of(new UpdateViewDistancePacket(4)), updates.collect());
        assertEquals(5, player.effectiveViewDistance());

        updates = connection.trackIncoming(UpdateViewDistancePacket.class);
        player.setInstance(far, SPAWN).join();
        assertEquals(List.of(new UpdateViewDistancePacket(12)), updates.collect());
    }

    @Test
    public void aCapOutOfTheClientRangeIsRefused(Env env) {
        final Player player = env.createPlayer(env.createFlatInstance(), SPAWN);
        assertEquals(32, player.getMaxViewDistance(), "no cap by default");
        assertThrows(IllegalArgumentException.class, () -> player.setMaxViewDistance(1));
        assertThrows(IllegalArgumentException.class, () -> player.setMaxViewDistance(33));
        assertEquals(32, player.getMaxViewDistance());
    }
}
