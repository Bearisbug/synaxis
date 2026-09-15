package io.github.bearisbug.synaxis.entity.player;

import net.kyori.adventure.resource.ResourcePackCallback;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackStatus;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientResourcePackStatusPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.kyori.adventure.resource.ResourcePackRequest.resourcePackRequest;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
