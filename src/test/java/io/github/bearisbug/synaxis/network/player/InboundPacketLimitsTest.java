package io.github.bearisbug.synaxis.network.player;

import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.entity.PlayerHand;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientAnimationPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientTabCompletePacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientTickEndPacket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.github.bearisbug.synaxis.network.player.InboundPacketLimits.Verdict.ACCEPT;
import static io.github.bearisbug.synaxis.network.player.InboundPacketLimits.Verdict.DROP;
import static io.github.bearisbug.synaxis.network.player.InboundPacketLimits.Verdict.KICK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The counting rules of {@code docs/DESIGN-PROD.md} {@code API-PROD-012}: the sliding window, the
 * per type overrides and the two actions.
 */
public class InboundPacketLimitsTest {

    private static final long MILLI = TimeUnit.MILLISECONDS.toNanos(1);

    @Test
    public void theDefaultsAreTwiceTheBaselineP99InSevenSeconds() {
        final ServerConfig defaults = ServerConfig.defaults();
        assertEquals(7_000, defaults.packetLimitWindow());
        assertEquals(750, defaults.packetLimitMaxPackets());
        assertEquals(PacketLimitAction.KICK, defaults.packetLimitAction());
        assertEquals(4L << 20, defaults.maxInflatedBytesPerSecond());
        assertEquals(20, defaults.tabCompleteRatePerSecond());
    }

    @Test
    public void theWindowSlides() {
        final InboundPacketLimits.Limiter limiter = limiter(ServerConfig.builder()
                .packetLimitWindow(1_000).packetLimitMaxPackets(10));
        final long start = 1_000_000 * MILLI;
        for (int i = 0; i < 10; i++) assertEquals(ACCEPT, limiter.admit(new ClientTickEndPacket(), start));
        assertEquals(KICK, limiter.admit(new ClientTickEndPacket(), start + 500 * MILLI));
        // One window later the first ten have left it
        assertEquals(ACCEPT, limiter.admit(new ClientTickEndPacket(), start + 1_100 * MILLI));
    }

    @Test
    public void theAllPacketsLimitCanDropInstead() {
        final InboundPacketLimits.Limiter limiter = limiter(ServerConfig.builder()
                .packetLimitMaxPackets(3).packetLimitAction(PacketLimitAction.DROP));
        assertEquals(List.of(ACCEPT, ACCEPT, ACCEPT, DROP, DROP), admit(limiter, 5, new ClientTickEndPacket()));
    }

    @Test
    public void anOverrideDropsItsOwnTypeOnly() {
        final InboundPacketLimits.Limiter limiter = limiter(ServerConfig.builder()
                .packetLimitOverrides("ClientAnimationPacket=2:DROP"));
        assertEquals(List.of(ACCEPT, ACCEPT, DROP, DROP), admit(limiter, 4, new ClientAnimationPacket(PlayerHand.MAIN)));
        assertEquals(ACCEPT, limiter.admit(new ClientTickEndPacket(), 0));
    }

    @Test
    public void anOverrideCanKick() {
        final InboundPacketLimits.Limiter limiter = limiter(ServerConfig.builder()
                .packetLimitOverrides(" ClientAnimationPacket = 2 : kick "));
        assertEquals(List.of(ACCEPT, ACCEPT, KICK), admit(limiter, 3, new ClientAnimationPacket(PlayerHand.MAIN)));
    }

    @Test
    public void aBadOverrideRefusesTheConfiguration() {
        for (String overrides : List.of("ClientAnimationPacket", "ClientAnimationPacket=2", "NoSuchPacket=2:DROP",
                "ClientAnimationPacket=0:DROP", "ClientAnimationPacket=2:WARN",
                "ClientAnimationPacket=2:DROP,ClientAnimationPacket=3:KICK")) {
            assertThrows(IllegalArgumentException.class,
                    () -> ServerConfig.builder().packetLimitOverrides(overrides).build(), overrides);
        }
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder().packetLimitMaxPackets(0).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder().packetLimitWindow(0).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder().maxInflatedBytesPerSecond(0).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder().tabCompleteRatePerSecond(0).build());
    }

    @Test
    public void emptyOverrideEntriesAreSkipped() {
        assertEquals(Map.of(ClientTabCompletePacket.class, new InboundPacketLimits.Rule(40, PacketLimitAction.DROP)),
                InboundPacketLimits.parseOverrides("ClientTabCompletePacket=40:DROP,"));
        assertEquals(Map.of(), InboundPacketLimits.parseOverrides(",,"));
    }

    @Test
    public void inflatedBytesAreCountedPerSecond() {
        final InboundPacketLimits.Limiter limiter = limiter(ServerConfig.builder().maxInflatedBytesPerSecond(100));
        final long start = 1_000_000 * MILLI;
        assertTrue(limiter.admitInflated(60, start));
        assertFalse(limiter.admitInflated(60, start + 10 * MILLI));
        assertTrue(limiter.admitInflated(60, start + 1_200 * MILLI));
    }

    private static InboundPacketLimits.Limiter limiter(ServerConfig.Builder builder) {
        return InboundPacketLimits.of(builder.build()).newLimiter();
    }

    private static List<InboundPacketLimits.Verdict> admit(InboundPacketLimits.Limiter limiter, int count,
                                                           ClientPacket packet) {
        final List<InboundPacketLimits.Verdict> verdicts = new ArrayList<>();
        for (int i = 0; i < count; i++) verdicts.add(limiter.admit(packet, 0));
        return verdicts;
    }
}
