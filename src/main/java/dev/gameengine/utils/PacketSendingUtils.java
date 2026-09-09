package dev.gameengine.utils;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.audience.ForwardingAudience;
import dev.gameengine.ServerProcess;
import dev.gameengine.adventure.audience.PacketGroupingAudience;
import dev.gameengine.entity.Player;
import dev.gameengine.network.packet.server.CachedPacket;
import dev.gameengine.network.packet.server.SendablePacket;
import dev.gameengine.network.packet.server.ServerPacket;

import java.util.Collection;
import java.util.function.Predicate;

public final class PacketSendingUtils {
    /**
     * Sends a packet to an audience. This method performs the following steps in the
     * following order:
     * <ol>
     *     <li>If {@code audience} is a {@link Player}, send the packet to them.</li>
     *     <li>Otherwise, if {@code audience} is a {@link PacketGroupingAudience}, call
     *     {@link #sendGroupedPacket(Collection, ServerPacket)} on the players that the
     *     grouping audience contains.</li>
     *     <li>Otherwise, if {@code audience} is a {@link ForwardingAudience.Single},
     *     call this method on the single audience inside the forwarding audience.</li>
     *     <li>Otherwise, if {@code audience} is a {@link ForwardingAudience}, call this
     *     method for each audience member of the forwarding audience.</li>
     *     <li>Otherwise, do nothing.</li>
     * </ol>
     *
     * @param audience the audience
     * @param packet   the packet
     */
    @SuppressWarnings("OverrideOnly") // we need to access the audiences inside ForwardingAudience
    public static void sendPacket(Audience audience, ServerPacket packet) {
        switch (audience) {
            case Player player -> player.sendPacket(packet);
            case PacketGroupingAudience groupingAudience -> sendGroupedPacket(groupingAudience.getPlayers(), packet);
            case ForwardingAudience.Single singleAudience -> sendPacket(singleAudience.audience(), packet);
            case ForwardingAudience forwardingAudience -> {
                for (Audience member : forwardingAudience.audiences()) {
                    sendPacket(member, packet);
                }
            }
            default -> {
            }
        }
    }

    /**
     * Sends a {@link ServerPacket} to multiple players.
     * <p>
     * Can drastically improve performance since the packet will not have to be processed as much.
     *
     * @param players   the players to send the packet to
     * @param packet    the packet to send to the players
     * @param predicate predicate to ignore specific players
     */
    public static <T extends Player> void sendGroupedPacket(Collection<T> players, ServerPacket packet,
                                         Predicate<? super T> predicate) {
        final SendablePacket sendablePacket = CachedPacket.grouped(packet);
        players.forEach(player -> {
            if (predicate.test(player)) player.sendPacket(sendablePacket);
        });
    }

    /**
     * Same as {@link #sendGroupedPacket(Collection, ServerPacket, Predicate)}
     * but without any predicate.
     *
     * @see #sendGroupedPacket(Collection, ServerPacket, Predicate)
     */
    public static void sendGroupedPacket(Collection<? extends Player> players, ServerPacket packet) {
        final SendablePacket sendablePacket = CachedPacket.grouped(packet);
        players.forEach(player -> player.sendPacket(sendablePacket));
    }

    public static void broadcastPlayPacket(ServerProcess process, ServerPacket packet) {
        sendGroupedPacket(process.connection().getOnlinePlayers(), packet);
    }
}
