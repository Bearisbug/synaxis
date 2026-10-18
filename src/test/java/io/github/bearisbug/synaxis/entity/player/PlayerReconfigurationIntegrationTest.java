package io.github.bearisbug.synaxis.entity.player;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.EquipmentSlotGroup;
import io.github.bearisbug.synaxis.entity.GameMode;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.entity.attribute.Attribute;
import io.github.bearisbug.synaxis.entity.attribute.AttributeModifier;
import io.github.bearisbug.synaxis.entity.attribute.AttributeOperation;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.item.component.AttributeList;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChangeGameStatePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityStatusPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.HeldItemChangePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.PlayerAbilitiesPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SetExperiencePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SystemChatPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.UpdateHealthPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class PlayerReconfigurationIntegrationTest {

    @Test
    public void playPacketsAreNotSentAfterLeavingPlay(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        Player player = connection.connect(instance, new Pos(0, 40, 0));

        player.startConfigurationPhase();
        env.process().connection().updateWaitingPlayers();
        assertFalse(player.isActive(), "a player sent back to configuration is not spawned in any instance");

        // The connection is about to be reconfigured, play packets written to it are a protocol
        // error and get the player disconnected by the write thread.
        var gameStates = connection.trackIncoming(ChangeGameStatePacket.class);
        var abilities = connection.trackIncoming(PlayerAbilitiesPacket.class);
        player.setGameMode(GameMode.CREATIVE);

        gameStates.assertCount(0);
        abilities.assertCount(0);
    }

    /**
     * The setters that only send a play packet may be called while the player is being configured: the
     * packet would reach a connection in the configuration state, or a client with no player yet, and
     * get the player disconnected. The client is told the values when the player joins.
     */
    @Test
    public void settersCalledInTheConfigurationQueueNoPlayPacket(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection(new GameProfile(UUID.randomUUID(), "Configured"));
        var trackers = playPacketTrackers(connection);
        var experience = connection.trackIncoming(SetExperiencePacket.class);
        var held = connection.trackIncoming(HeldItemChangePacket.class);
        var abilities = connection.trackIncoming(PlayerAbilitiesPacket.class);
        final List<String> queuedInConfiguration = new CopyOnWriteArrayList<>();
        env.process().eventHandler().addListener(AsyncPlayerConfigurationEvent.class, event -> {
            final Player player = event.getPlayer();
            player.setHealth(10);
            player.setFood(10);
            player.setFoodSaturation(2);
            player.setExp(0.5f);
            player.setLevel(7);
            player.setHeldItemSlot((byte) 3);
            player.setAllowFlying(true);
            player.setReducedDebugScreenInformation(true);
            player.setEnableRespawnScreen(false);
            player.sendMessage(Component.text("welcome"));
            for (var tracker : trackers)
                for (var packet : tracker.collect()) queuedInConfiguration.add(packet.getClass().getSimpleName());
        });

        connection.connect(instance, new Pos(0, 40, 0));

        assertEquals(List.of(), queuedInConfiguration, "play packets were queued in the configuration state");
        experience.assertAnyMatch(packet -> packet.percentage() == 0.5f && packet.level() == 7);
        held.assertAnyMatch(packet -> packet.slot() == 3);
        abilities.assertAnyMatch(packet -> (packet.flags() & PlayerAbilitiesPacket.FLAG_ALLOW_FLYING) != 0);
    }

    @Test
    public void settersCalledAfterLeavingPlayQueueNoPlayPacket(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        Player player = connection.connect(instance, new Pos(0, 40, 0));

        player.startConfigurationPhase();
        env.process().connection().updateWaitingPlayers();
        var trackers = playPacketTrackers(connection);
        player.setHealth(4);
        player.setFood(4);
        player.setExp(0.25f);
        player.setLevel(3);
        player.setHeldItemSlot((byte) 2);
        player.setFlying(true);
        player.setReducedDebugScreenInformation(true);
        player.sendMessage(Component.text("still there?"));

        for (var tracker : trackers) tracker.assertEmpty();
    }

    /** The packets the setters of the two tests above send, and the system chat of sendMessage. */
    private static List<Collector<? extends ServerPacket>> playPacketTrackers(TestConnection connection) {
        return List.of(connection.trackIncoming(UpdateHealthPacket.class),
                connection.trackIncoming(SetExperiencePacket.class),
                connection.trackIncoming(HeldItemChangePacket.class),
                connection.trackIncoming(PlayerAbilitiesPacket.class),
                connection.trackIncoming(EntityStatusPacket.class),
                connection.trackIncoming(ChangeGameStatePacket.class),
                connection.trackIncoming(SystemChatPacket.class));
    }

    /** Equipment put into the inventory before the player joins counts once it has joined. */
    @Test
    public void equipmentGivenInTheConfigurationAppliesItsModifiers(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection(new GameProfile(UUID.randomUUID(), "Armed"));
        final ItemStack sword = ItemStack.of(Material.STICK).with(DataComponents.ATTRIBUTE_MODIFIERS,
                new AttributeList(new AttributeList.Modifier(Attribute.ATTACK_DAMAGE,
                        new AttributeModifier("synaxis:test_sword", 5, AttributeOperation.ADD_VALUE),
                        EquipmentSlotGroup.MAIN_HAND)));
        env.process().eventHandler().addListener(AsyncPlayerConfigurationEvent.class,
                event -> event.getPlayer().getInventory().setItemStack(0, sword));

        Player player = connection.connect(instance, new Pos(0, 40, 0));

        final double base = player.getAttribute(Attribute.ATTACK_DAMAGE).getBaseValue();
        assertEquals(base + 5, player.getAttributeValue(Attribute.ATTACK_DAMAGE),
                "the sword held from the start must count");
    }

    @Test
    public void aPlayerBackFromConfigurationSpawnsAgainOnItsFormerTickThread(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        Player player = connection.connect(instance, new Pos(0, 40, 0));
        env.tick();
        assertNotNull(player.acquirable().assignedThread(), "the first tick hands the player to a tick thread");

        var connections = env.process().connection();
        player.acquirable().sync(p -> {
            p.startConfigurationPhase();
            connections.updateWaitingPlayers();
        });
        assertFalse(player.isActive(), "a player sent back to configuration is not spawned in any instance");

        // Back to play the way the login got there: the configuration on a virtual thread, then the join.
        // The test connection never answered the known packs query of the login, a client does.
        player.getPlayerConnection().receiveKnownPacksResponse(List.of());
        var configured = new CompletableFuture<Void>();
        Thread.startVirtualThread(() -> {
            try {
                connections.doConfiguration(player, false);
                connections.transitionConfigToPlay(player);
                configured.complete(null);
            } catch (Throwable t) {
                configured.completeExceptionally(t);
            }
        });
        configured.join();
        // Still assigned to the tick thread of its first spawn: the spawn writes its health on that thread's hold
        connections.updateWaitingPlayers();

        assertTrue(player.isOnline(), "the second spawn kept the player");
        assertSame(instance, player.getInstance(), "the player spawned again in the instance of its configuration");
    }
}
