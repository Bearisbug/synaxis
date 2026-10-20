package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.event.EventFilter;
import io.github.bearisbug.synaxis.listener.PlayerInputListener;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientInputPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class PlayerInputEventIntegrationTest {

    @Test
    public void anEventKeepsTheInputsItWasFiredFor(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createConnection().connect(instance, new Pos(0, 40, 0));
        var events = env.trackEvent(PlayerInputEvent.class, EventFilter.PLAYER, player);

        PlayerInputListener.listener(new ClientInputPacket(true, false, false, false, true, false, false), player);
        PlayerInputListener.listener(new ClientInputPacket(false, true, false, false, false, false, false), player);

        final List<PlayerInputEvent> fired = events.collect();
        assertEquals(2, fired.size());
        final PlayerInputEvent first = fired.getFirst();
        assertTrue(first.isHoldingForwardKey());
        assertTrue(first.hasPressedForwardKey());
        assertTrue(first.isHoldingJumpKey());
        assertFalse(first.isHoldingBackwardKey());
        assertFalse(first.hasPressedBackwardKey());

        final PlayerInputEvent second = fired.getLast();
        assertTrue(second.hasReleasedForwardKey());
        assertTrue(second.hasReleasedJumpKey());
        assertTrue(second.hasPressedBackwardKey());
    }
}
