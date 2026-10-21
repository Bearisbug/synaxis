package io.github.bearisbug.synaxis.adventure.audience;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.audience.ForwardingAudience;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.TitlePart;
import io.github.bearisbug.synaxis.adventure.AdventurePacketConvertor;
import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.message.ChatPosition;
import io.github.bearisbug.synaxis.message.Messenger;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ActionBarPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ClearTitlesPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.PlayerListHeaderAndFooterPacket;
import io.github.bearisbug.synaxis.network.PacketSendingUtils;

import java.util.Collection;
import java.util.Iterator;

/**
 * An audience implementation that sends grouped packets if possible.
 *
 * <p>All players of the audience must belong to the same {@code ServerProcess}: boss bars are shown and hidden
 * through the boss bar manager of the process of the first player.</p>
 */
public interface PacketGroupingAudience extends ForwardingAudience {
    /**
     * Creates a packet grouping audience backed by the given collection; the collection is not copied, so later
     * changes are reflected. Pass a snapshot, {@code List.copyOf(players)} for instance, to fix the members.
     *
     * @param players the players
     * @return the audience
     */
    static PacketGroupingAudience of(Collection<? extends Player> players) {
        return () -> players;
    }

    /**
     * Gets an iterable of the players this audience contains.
     *
     * @return the players
     */
    Collection<? extends Player> getPlayers();

    /**
     * Broadcast a ServerPacket to all players of this audience
     *
     * @param packet the packet to broadcast
     */
    default void sendGroupedPacket(ServerPacket packet) {
        PacketSendingUtils.sendGroupedPacket(getPlayers(), packet);
    }

    @Override
    default void sendMessage(Component message) {
        Messenger.sendMessage(this.getPlayers(), message, ChatPosition.SYSTEM_MESSAGE);
    }

    @Override
    default void sendActionBar(Component message) {
        sendGroupedPacket(new ActionBarPacket(message));
    }

    @Override
    default void sendPlayerListHeaderAndFooter(Component header, Component footer) {
        sendGroupedPacket(new PlayerListHeaderAndFooterPacket(header, footer));
    }

    @Override
    default <T> void sendTitlePart(TitlePart<T> part, T value) {
        sendGroupedPacket(AdventurePacketConvertor.createTitlePartPacket(part, value));
    }

    @Override
    default void clearTitle() {
        sendGroupedPacket(new ClearTitlesPacket(false));
    }

    @Override
    default void resetTitle() {
        sendGroupedPacket(new ClearTitlesPacket(true));
    }

    @Override
    default void showBossBar(BossBar bar) {
        final Collection<? extends Player> players = this.getPlayers();
        // One iterator: the players may be a live collection, the last of them can leave between two calls
        final Iterator<? extends Player> iterator = players.iterator();
        if (!iterator.hasNext()) return;
        iterator.next().process().bossBar().addBossBar(players, bar);
    }

    @Override
    default void hideBossBar(BossBar bar) {
        final Collection<? extends Player> players = this.getPlayers();
        final Iterator<? extends Player> iterator = players.iterator();
        if (!iterator.hasNext()) return;
        iterator.next().process().bossBar().removeBossBar(players, bar);
    }

    /**
     * Plays a {@link Sound} at a given point
     * @param sound The sound to play
     * @param point the position to play the sound at
     */
    default void playSound(Sound sound, Point point) {
        playSound(sound, point.x(), point.y(), point.z());
    }

    @Override
    default void playSound(Sound sound, double x, double y, double z) {
        sendGroupedPacket(AdventurePacketConvertor.createSoundPacket(sound, x, y, z));
    }

    @Override
    default void playSound(Sound sound, Sound.Emitter emitter) {
        if (emitter != Sound.Emitter.self()) {
            sendGroupedPacket(AdventurePacketConvertor.createSoundPacket(sound, emitter));
        } else {
            // if we're playing on self, we need to delegate to each audience member
            for (Audience audience : this.audiences()) {
                audience.playSound(sound, emitter);
            }
        }
    }

    @Override
    default void stopSound(SoundStop stop) {
        sendGroupedPacket(AdventurePacketConvertor.createSoundStopPacket(stop));
    }

    @Override
    default Iterable<? extends Audience> audiences() {
        return this.getPlayers();
    }
}
