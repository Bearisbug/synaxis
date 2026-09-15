package io.github.bearisbug.synaxis.entity.player;

import net.kyori.adventure.resource.ResourcePackCallback;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackStatus;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientResourcePackStatusPacket;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.ResourcePackPushPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.kyori.adventure.resource.ResourcePackRequest.resourcePackRequest;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
class PlayerResourcePackIntegrationTest {
    private static final ResourcePackInfo INFO = ResourcePackInfo.resourcePackInfo(UUID.randomUUID(), URI.create("http://localhost:8080/missing.zip"), "i am not a hash!");

    @Test
    void applyCallbackOnSuccess(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 40, 0));

        AtomicBoolean called = new AtomicBoolean();
        ResourcePackCallback callback = (_, _, _) -> called.set(true);

        player.sendResourcePacks(resourcePackRequest().callback(callback).packs(INFO).build());
        player.addPacketToQueue(new ClientResourcePackStatusPacket(INFO.id(), ResourcePackStatus.SUCCESSFULLY_LOADED));

        player.interpretPacketQueue();

        assertTrue(called.get());
        assertTrue(player.isOnline());
    }

    @Test
    void applyFailRequiredKicksPlayer(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 40, 0));

        player.sendResourcePacks(resourcePackRequest().required(true).packs(INFO).build());
        player.addPacketToQueue(new ClientResourcePackStatusPacket(INFO.id(), ResourcePackStatus.FAILED_RELOAD));

        player.interpretPacketQueue();

        assertFalse(player.isOnline());
    }

    @Test
    void applyFailNotRequiredDoesNotKickPlayer(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 40, 0));

        player.sendResourcePacks(resourcePackRequest().required(false).packs(INFO).build());
        player.addPacketToQueue(new ClientResourcePackStatusPacket(INFO.id(), ResourcePackStatus.FAILED_RELOAD));

        player.interpretPacketQueue();

        assertTrue(player.isOnline());
    }

    @Test
    void leavingCompletesThePendingResourcePackWait(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 40, 0));

        player.sendResourcePacks(resourcePackRequest().packs(INFO).build());
        var future = player.getResourcePackFuture();
        assertNotNull(future);
        assertFalse(future.isDone(), "the client has not answered yet");

        // What PlayerConnection#disconnect does when the client closes the socket on the pack prompt
        env.process().connection().removePlayer(player.getPlayerConnection());

        assertTrue(future.isDone(), "the configuration thread waits on this future and is never released");
    }

    @Test
    void statusReceivedWhilePushingIsNotLost(Env env) {
        env.process().connection().setPlayerProvider(EagerClientPlayer::new);
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 40, 0));

        player.sendResourcePacks(resourcePackRequest().packs(INFO).build());

        assertNull(player.getResourcePackFuture(),
                "the status answered during the push was dropped, the configuration thread waits forever");
    }

    /**
     * A client answering the push packet before {@link Player#sendResourcePacks} returns, which is what
     * happens when the status packet is interpreted by the ticker thread while the configuration thread
     * is still pushing.
     */
    private static final class EagerClientPlayer extends Player {
        private EagerClientPlayer(PlayerConnection playerConnection, GameProfile gameProfile) {
            super(playerConnection, gameProfile);
        }

        @Override
        public void sendPacket(SendablePacket packet) {
            super.sendPacket(packet);
            if (packet instanceof ResourcePackPushPacket push)
                onResourcePackStatus(push.id(), ResourcePackStatus.SUCCESSFULLY_LOADED);
        }
    }
}
