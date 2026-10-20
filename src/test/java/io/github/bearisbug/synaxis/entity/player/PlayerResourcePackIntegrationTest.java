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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
class PlayerResourcePackIntegrationTest {
    private static final ResourcePackInfo INFO = ResourcePackInfo.resourcePackInfo(UUID.randomUUID(), URI.create("http://localhost:8080/missing.zip"), "i am not a hash!");
    private static final ResourcePackInfo OTHER = ResourcePackInfo.resourcePackInfo(UUID.randomUUID(), URI.create("http://localhost:8080/other.zip"), "nor am i");

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
     * A replacing request keeps the wait whoever waits on the packs already holds: it ends once the new pack is answered.
     * The pack it replaced is forgotten, the client discarding it neither calls its callback nor kicks for it.
     */
    @Test
    void replacingKeepsTheWaitForTheNewPackAndForgetsTheOldOne(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 40, 0));
        AtomicBoolean replacedCalled = new AtomicBoolean();

        player.sendResourcePacks(resourcePackRequest().required(true).callback((_, _, _) -> replacedCalled.set(true)).packs(INFO).build());
        var future = player.getResourcePackFuture();
        assertNotNull(future);
        player.sendResourcePacks(resourcePackRequest().replace(true).packs(OTHER).build());

        assertSame(future, player.getResourcePackFuture(), "the replacing request handed out another wait");
        assertFalse(future.isDone(), "the new pack has not been answered yet");
        player.addPacketToQueue(new ClientResourcePackStatusPacket(INFO.id(), ResourcePackStatus.DISCARDED));
        player.addPacketToQueue(new ClientResourcePackStatusPacket(OTHER.id(), ResourcePackStatus.SUCCESSFULLY_LOADED));
        player.interpretPacketQueue();

        assertTrue(future.isDone(), "the wait outlived the answer to the new pack");
        assertFalse(replacedCalled.get(), "the callback of the replaced pack was called");
        assertTrue(player.isOnline(), "the replaced required pack kicked the player");
    }

    /** Removing or clearing the last packs ends the wait, and the answer to a pack taken back is ignored. */
    @Test
    void packsTakenBackEndTheWaitAndTheirAnswerIsIgnored(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 40, 0));
        AtomicBoolean called = new AtomicBoolean();

        player.sendResourcePacks(resourcePackRequest().required(true).callback((_, _, _) -> called.set(true)).packs(INFO).build());
        var removed = player.getResourcePackFuture();
        assertNotNull(removed);
        player.removeResourcePacks(INFO.id());
        assertTrue(removed.isDone(), "the wait outlived the removal of its last pack");
        assertNull(player.getResourcePackFuture());
        player.addPacketToQueue(new ClientResourcePackStatusPacket(INFO.id(), ResourcePackStatus.DISCARDED));
        player.interpretPacketQueue();
        assertFalse(called.get(), "the callback of the removed pack was called");
        assertTrue(player.isOnline(), "the removed required pack kicked the player");

        player.sendResourcePacks(resourcePackRequest().packs(OTHER).build());
        var cleared = player.getResourcePackFuture();
        assertNotNull(cleared);
        player.clearResourcePacks();
        assertTrue(cleared.isDone(), "the wait outlived the clearing of the packs");
        assertNull(player.getResourcePackFuture());
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
