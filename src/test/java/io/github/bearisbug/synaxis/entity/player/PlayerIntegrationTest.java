package io.github.bearisbug.synaxis.entity.player;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityStatuses;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.entity.GameMode;
import io.github.bearisbug.synaxis.entity.MainHand;
import io.github.bearisbug.synaxis.entity.MetadataDef;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.coordinate.RelativeFlags;
import io.github.bearisbug.synaxis.entity.damage.DamageType;
import io.github.bearisbug.synaxis.event.EventFilter;
import io.github.bearisbug.synaxis.event.player.PlayerChunkUnloadEvent;
import io.github.bearisbug.synaxis.event.player.PlayerGameModeChangeEvent;
import io.github.bearisbug.synaxis.event.player.PlayerInputEvent;
import io.github.bearisbug.synaxis.listener.PlayerInputListener;
import io.github.bearisbug.synaxis.message.ChatMessageType;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientSettingsPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientInputPacket;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.DeclareCommandsPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityAttributesPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityMetaDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityStatusPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.FacePlayerPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.JoinGamePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.PlayerAbilitiesPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.PlayerInfoUpdatePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.PlayerPositionAndLookPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ServerDifficultyPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SetExperiencePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SpawnPositionPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.UpdateHealthPacket;
import io.github.bearisbug.synaxis.network.player.ClientSettings;
import io.github.bearisbug.synaxis.world.DimensionType;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class PlayerIntegrationTest {

    /**
     * Test to see whether player abilities are updated correctly and events
     * are handled properly when changing gamemode.
     */
    @Test
    public void gamemodeTest(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        assertEquals(instance, player.getInstance());

        // Abilities
        {
            player.setGameMode(GameMode.CREATIVE);
            assertAbilities(player, true, false, true, true);
            player.setGameMode(GameMode.SPECTATOR);
            assertAbilities(player, true, true, true, false);
            player.setGameMode(GameMode.CREATIVE);
            assertAbilities(player, true, true, true, true);
            player.setGameMode(GameMode.ADVENTURE);
            assertAbilities(player, false, false, false, false);
            player.setGameMode(GameMode.SURVIVAL);
            assertAbilities(player, false, false, false, false);
        }

        var listener = env.listen(PlayerGameModeChangeEvent.class);
        // Normal change
        {
            listener.followup();
            assertTrue(player.setGameMode(GameMode.ADVENTURE));
        }
        // Change target gamemode event
        {
            listener.followup(event -> event.setNewGameMode(GameMode.SPECTATOR));
            assertTrue(player.setGameMode(GameMode.CREATIVE));
            assertEquals(GameMode.SPECTATOR, player.getGameMode());
        }
        // Cancel event
        {
            listener.followup(event -> event.setCancelled(true));
            assertFalse(player.setGameMode(GameMode.CREATIVE));
            assertEquals(GameMode.SPECTATOR, player.getGameMode());
        }
    }

    @Test
    public void handSwapTest(Env env) {
        ClientSettingsPacket packet = new ClientSettingsPacket(new ClientSettings(
                Locale.US, (byte) 16,
                ChatMessageType.FULL, true,
                (byte) 127, MainHand.LEFT,
                true, true,
                ClientSettings.ParticleSetting.ALL
        ));

        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        assertEquals(instance, player.getInstance());
        env.tick();
        env.tick();

        player.addPacketToQueue(packet);
        var collector = connection.trackIncoming();
        env.tick();
        env.tick();
        assertEquals(MainHand.LEFT, player.getSettings().mainHand());

        boolean found = false;
        for (ServerPacket serverPacket : collector.collect()) {
            if (!(serverPacket instanceof EntityMetaDataPacket metaDataPacket)) {
                continue;
            }
            assertEquals(MainHand.LEFT, metaDataPacket.entries().get(MetadataDef.Player.MAIN_HAND.index()).value(),
                    "EntityMetaDataPacket has the incorrect hand after client settings update.");
            found = true;
        }
        assertTrue(found, "EntityMetaDataPacket not sent after client settings update.");

        assertEquals(ClientSettings.ParticleSetting.ALL, player.getSettings().particleSetting());
    }

    private static void assertAbilities(Player player, boolean isInvulnerable, boolean isFlying, boolean isAllowFlying,
                                 boolean isInstantBreak) {
        assertEquals(isInvulnerable, player.isInvulnerable());
        assertEquals(isFlying, player.isFlying());
        assertEquals(isAllowFlying, player.isAllowFlying());
        assertEquals(isInstantBreak, player.isInstantBreak());
    }

    @Test
    public void playerJoinPackets(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();

        final var packets = List.of(
                JoinGamePacket.class, ServerDifficultyPacket.class, SpawnPositionPacket.class,
                DeclareCommandsPacket.class, EntityAttributesPacket.class, EntityStatusPacket.class,
                UpdateHealthPacket.class, PlayerAbilitiesPacket.class
        );
        final List<Collector<?>> trackers = new ArrayList<>();
        for (var packet : packets) {
            trackers.add(connection.trackIncoming(packet));
        }

        var trackerAll = connection.trackIncoming(ServerPacket.class);

        var player = connection.connect(instance, new Pos(0, 40, 0));
        assertEquals(instance, player.getInstance());
        assertEquals(new Pos(0, 40, 0), player.getPosition());

        for (var tracker : trackers) {
            assertEquals(1, tracker.collect().size());
        }
        assertTrue(trackerAll.collect().size() > packets.size());
    }

    /**
     * Test to see whether the packets from Player#refreshPlayer are sent
     * when changing dimensions
     */
    @Test
    public void refreshPlayerTest(Env env) {
        final int TEST_PERMISSION_LEVEL = 2;
        final var testDimension = env.process().dimensionType().register(Key.key("synaxis:test_dimension"), DimensionType.builder().build());

        var instance = env.createFlatInstance();
        var instance2 = env.process().instance().createInstanceContainer(testDimension);

        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        assertEquals(instance, player.getInstance());

        var tracker1 = connection.trackIncoming(UpdateHealthPacket.class);
        var tracker2 = connection.trackIncoming(SetExperiencePacket.class);
        var trackerStatus = connection.trackIncoming(EntityStatusPacket.class);
        var tracker4 = connection.trackIncoming(PlayerAbilitiesPacket.class);

        player.setPermissionLevel(TEST_PERMISSION_LEVEL);

        // #join may cause the thread to hang as scheduled for the next tick when initially in a pool
        Assertions.assertTimeout(Duration.ofSeconds(15), () -> player.setInstance(instance2).join());
        assertEquals(instance2, player.getInstance());

        assertEquals(1, tracker1.collect().size());
        assertEquals(1, tracker2.collect().size());
        assertEquals(2, trackerStatus.collect().size());
        assertEquals(1, tracker4.collect().size());

        // Ensure that the player was sent the permission levels
        for (var statusPacket : trackerStatus.collect()) {
            assertEquals(player.getEntityId(), statusPacket.entityId());
            assertEquals(EntityStatuses.Player.PERMISSION_LEVEL_2, statusPacket.status());
        }
    }

    @Test
    public void deathLocationTest(Env env) {
        String dimensionNamespace = "synaxis:test_dimension";
        final var testDimension = env.process().dimensionType().register(Key.key(dimensionNamespace), DimensionType.builder().build());

        var instance = env.process().instance().createInstanceContainer(testDimension);
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(5, 42, 2));

        assertNull(player.getDeathLocation());
        player.damage(DamageType.OUT_OF_WORLD, 30);

        assertNotNull(player.getDeathLocation());
        assertEquals(dimensionNamespace, player.getDeathLocation().dimension());
        assertEquals(5, player.getDeathLocation().blockPosition().x());
    }

    @Test
    public void fullInfoSync(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var tracker = connection.trackIncoming(PlayerInfoUpdatePacket.class);
        var _ = connection.connect(instance, new Pos(0, 42, 0));
        tracker.assertSingle(
                it -> assertEquals(EnumSet.allOf(PlayerInfoUpdatePacket.Action.class), it.actions(), "Not fully synced on join")
        );

        var connection2 = env.createConnection();
        var tracker2 = connection2.trackIncoming(PlayerInfoUpdatePacket.class);
        var _ = connection2.connect(instance, new Pos(5, 42, 0));
        tracker2.assertCount(2, packet -> packet.actions().equals(EnumSet.allOf(PlayerInfoUpdatePacket.Action.class)));
    }

    @Test
    public void displayNameTest(Env env) {
        Predicate<PlayerInfoUpdatePacket> predicate =
                packet -> packet.actions().contains(PlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME);
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var tracker = connection.trackIncoming(PlayerInfoUpdatePacket.class);
        var player = connection.connect(instance, new Pos(0, 42, 0));

        player.setDisplayName(Component.text("Display Name!"));

        var connection2 = env.createConnection();
        var tracker2 = connection2.trackIncoming(PlayerInfoUpdatePacket.class);
        connection2.connect(instance, new Pos(0, 42, 0));

        tracker2.assertCount(2, predicate);

        var tracker3 = connection2.trackIncoming(PlayerInfoUpdatePacket.class);

        player.setDisplayName(Component.text("Other Name!"));

        tracker3.assertCount(1, predicate);
        tracker.assertCount(4, predicate);
    }

    @Test
    public void gameModeInfoTest(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var tracker = connection.trackIncoming(PlayerInfoUpdatePacket.class);
        var player = connection.connect(instance, new Pos(0, 42, 0));

        tracker.assertCount(1, packet ->
                packet.actions().contains(PlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE));
        var tracker2 = connection.trackIncoming(PlayerInfoUpdatePacket.class);

        player.setGameMode(GameMode.CREATIVE);

        tracker2.assertCount(1, packet ->
                packet.actions().contains(PlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE));
    }

    @Test
    public void latencyTest(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var tracker = connection.trackIncoming(PlayerInfoUpdatePacket.class);
        var player = connection.connect(instance, new Pos(0, 42, 0));

        tracker.assertCount(1, packet ->
                packet.actions().contains(PlayerInfoUpdatePacket.Action.UPDATE_LATENCY));

        var tracker2 = connection.trackIncoming(PlayerInfoUpdatePacket.class);
        player.refreshLatency(100);

        tracker2.assertCount(1, packet ->
                packet.actions().contains(PlayerInfoUpdatePacket.Action.UPDATE_LATENCY));
    }

    @Test
    public void listedTest(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var tracker = connection.trackIncoming(PlayerInfoUpdatePacket.class);
        var player = connection.connect(instance, new Pos(0, 42, 0));


        assertTrue(player.isListed());

        player.setListed(false);

        long listedPackets = tracker.collect().stream().filter((packet) ->
                        packet.actions().stream().anyMatch((act) -> act == PlayerInfoUpdatePacket.Action.UPDATE_LISTED))
                .count();

        assertEquals(2, listedPackets);
        assertFalse(player.isListed());
    }

    @Test
    public void listOrderTest(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var tracker = connection.trackIncoming(PlayerInfoUpdatePacket.class);
        var player = connection.connect(instance, new Pos(0, 42, 0));

        assertEquals(0, player.getListOrder());

        player.setListOrder(1);

        long orderPackets = tracker.collect().stream().filter((packet) ->
                        packet.actions().stream().anyMatch((act) -> act == PlayerInfoUpdatePacket.Action.UPDATE_LIST_ORDER))
                .count();

        assertEquals(2, orderPackets);
        assertEquals(1, player.getListOrder());
    }

    @Test
    public void setView(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        Pos startingPlayerPos = new Pos(0, 42, 0);
        var player = connection.connect(instance, startingPlayerPos);

        var tracker = connection.trackIncoming(PlayerPositionAndLookPacket.class);
        player.setView(30, 20);

        assertEquals(startingPlayerPos.withView(30, 20), player.getPosition());
        tracker.assertSingle(PlayerPositionAndLookPacket.class, packet -> {
            // Should be relative coord and velocity because we are only trying to change the view.
            assertEquals(RelativeFlags.COORD | RelativeFlags.DELTA_COORD, packet.flags());
            assertEquals(new Pos(0, 0, 0, 30, 20), packet.position());
        });
    }

    @Test
    public void lookAt(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var tracker = connection.trackIncoming(FacePlayerPacket.class);
        Pos startingPlayerPos = new Pos(0, 42, 0);
        var player = connection.connect(instance, startingPlayerPos);

        Point pointLookAt = new Vec(3, 3, 3);
        player.lookAt(pointLookAt);
        tracker.assertSingle(FacePlayerPacket.class, packet -> assertEquals(pointLookAt, packet.target()));

        tracker = connection.trackIncoming(FacePlayerPacket.class);
        Entity entity = new Entity(EntityType.ZOMBIE);
        entity.setInstance(player.getInstance(), new Pos(9, 9, 9)).join();
        player.lookAt(entity);
        tracker.assertSingle(FacePlayerPacket.class, packet -> assertEquals(entity.getEntityId(), packet.entityId()));

        assertEquals(startingPlayerPos, player.getPosition());
    }

    @Test
    public void removePlayer(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        Player player = connection.connect(instance, new Pos(0, 40, 0));

        instance.eventNode().addListener(PlayerChunkUnloadEvent.class, event -> {
            assertEquals(instance, event.getInstance());
            assertEquals(player, event.getPlayer());
        });

        player.remove(true);
    }

    @Test
    public void inputsPressed(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 40, 0));

        var events = env.trackEvent(PlayerInputEvent.class, EventFilter.PLAYER, player);
        PlayerInputListener.listener(
                new ClientInputPacket(true, true, true, true, true, true, true),
                player
        );

        events.assertSingle(event -> {
            assertFalse(event.hasReleasedForwardKey());
            assertFalse(event.hasReleasedBackwardKey());
            assertFalse(event.hasReleasedLeftKey());
            assertFalse(event.hasReleasedRightKey());
            assertFalse(event.hasReleasedJumpKey());
            assertFalse(event.hasReleasedShiftKey());
            assertFalse(event.hasReleasedSprintKey());

            assertTrue(event.hasPressedForwardKey());
            assertTrue(event.hasPressedBackwardKey());
            assertTrue(event.hasPressedLeftKey());
            assertTrue(event.hasPressedRightKey());
            assertTrue(event.hasPressedJumpKey());
            assertTrue(event.hasPressedShiftKey());
            assertTrue(event.hasPressedSprintKey());

            assertTrue(event.isHoldingForwardKey());
            assertTrue(event.isHoldingBackwardKey());
            assertTrue(event.isHoldingLeftKey());
            assertTrue(event.isHoldingRightKey());
            assertTrue(event.isHoldingJumpKey());
            assertTrue(event.isHoldingShiftKey());
            assertTrue(event.isHoldingSprintKey());
        });
    }

    @Test
    public void inputsReleased(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 40, 0));

        PlayerInputListener.listener(
                new ClientInputPacket(true, true, true, true, true, true, true),
                player
        );

        var events = env.trackEvent(PlayerInputEvent.class, EventFilter.PLAYER, player);
        PlayerInputListener.listener(
                new ClientInputPacket(false, false, false, false, false, false, false),
                player
        );

        events.assertSingle(event -> {
            assertFalse(event.hasPressedForwardKey());
            assertFalse(event.hasPressedBackwardKey());
            assertFalse(event.hasPressedLeftKey());
            assertFalse(event.hasPressedRightKey());
            assertFalse(event.hasPressedJumpKey());
            assertFalse(event.hasPressedShiftKey());
            assertFalse(event.hasPressedSprintKey());

            assertTrue(event.hasReleasedForwardKey());
            assertTrue(event.hasReleasedBackwardKey());
            assertTrue(event.hasReleasedLeftKey());
            assertTrue(event.hasReleasedRightKey());
            assertTrue(event.hasReleasedJumpKey());
            assertTrue(event.hasReleasedShiftKey());
            assertTrue(event.hasReleasedSprintKey());

            assertFalse(event.isHoldingForwardKey());
            assertFalse(event.isHoldingBackwardKey());
            assertFalse(event.isHoldingLeftKey());
            assertFalse(event.isHoldingRightKey());
            assertFalse(event.isHoldingJumpKey());
            assertFalse(event.isHoldingShiftKey());
            assertFalse(event.isHoldingSprintKey());
        });
    }

}
