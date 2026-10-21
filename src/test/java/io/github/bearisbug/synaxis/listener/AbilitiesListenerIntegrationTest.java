package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.EventFilter;
import io.github.bearisbug.synaxis.event.player.PlayerStartFlyingEvent;
import io.github.bearisbug.synaxis.event.player.PlayerStopFlyingEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerAbilitiesPacket;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client reports starting or stopping to fly with the flying bit of its abilities packet.
 * <p>
 * The player never ticks here: the test thread hands it its packets.
 */
@EnvTest
public class AbilitiesListenerIntegrationTest {

    private static void send(Player player, ClientPlayerAbilitiesPacket packet) {
        player.addPacketToQueue(packet);
        player.interpretPacketQueue();
    }

    @Test
    public void theFlyingBitIsReadAlone() {
        assertTrue(new ClientPlayerAbilitiesPacket((byte) 0x02).flying());
        assertTrue(new ClientPlayerAbilitiesPacket((byte) 0x07).flying());
        assertFalse(new ClientPlayerAbilitiesPacket((byte) 0x00).flying());
        assertFalse(new ClientPlayerAbilitiesPacket((byte) 0x05).flying());
    }

    @Test
    public void aPlayerAllowedToFlyStartsAndStopsWithTheFlyingBit(Env env) {
        final Instance instance = env.createFlatInstance();
        final Player player = env.createPlayer(instance, new Pos(0.5, 40, 0.5));
        player.setAllowFlying(true);
        final Collector<PlayerStartFlyingEvent> starts = env.trackEvent(PlayerStartFlyingEvent.class, EventFilter.PLAYER, player);
        final Collector<PlayerStopFlyingEvent> stops = env.trackEvent(PlayerStopFlyingEvent.class, EventFilter.PLAYER, player);

        // The other bits do not matter, only the flying one
        send(player, new ClientPlayerAbilitiesPacket((byte) 0x06));
        assertTrue(player.isFlying());
        starts.assertSingle();

        send(player, new ClientPlayerAbilitiesPacket((byte) 0x04));
        assertFalse(player.isFlying());
        stops.assertSingle();
    }
}
