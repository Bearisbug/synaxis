package io.github.bearisbug.synaxis.adventure.bossbar;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.network.PacketSendingUtils;
import io.github.bearisbug.synaxis.network.packet.server.play.BossBarPacket;

import java.util.Set;
import java.util.function.Function;

/**
 * A listener for boss bar updates. This class is not intended for public use, and it is
 * automatically added to boss bars shown to players using the methods in
 * {@link Audience}, instead you should use {@link BossBarManager} to manage boss bars
 * for players.
 */
class BossBarListener implements BossBar.Listener {
    private final BossBarManager manager;

    /**
     * Creates a new boss bar listener.
     *
     * @param manager the manager instance
     */
    BossBarListener(BossBarManager manager) {
        this.manager = manager;
    }

    @Override
    public void bossBarNameChanged(BossBar bar, Component oldName, Component newName) {
        this.send(bar, BossBarPacket.UpdateTitleAction::new);
    }

    @Override
    public void bossBarProgressChanged(BossBar bar, float oldProgress, float newProgress) {
        this.send(bar, BossBarPacket.UpdateHealthAction::new);
    }

    @Override
    public void bossBarColorChanged(BossBar bar, BossBar.Color oldColor, BossBar.Color newColor) {
        this.send(bar, BossBarPacket.UpdateStyleAction::new);
    }

    @Override
    public void bossBarOverlayChanged(BossBar bar, BossBar.Overlay oldOverlay, BossBar.Overlay newOverlay) {
        this.send(bar, BossBarPacket.UpdateStyleAction::new);
    }

    @Override
    public void bossBarFlagsChanged(BossBar bar, Set<BossBar.Flag> flagsAdded, Set<BossBar.Flag> flagsRemoved) {
        this.send(bar, BossBarPacket.UpdateFlagsAction::new);
    }

    /**
     * Sends a change to the viewers of a bar. Adventure sets the new value before it notifies the listeners, so the
     * action is built from the bar itself.
     *
     * @param bar    the bar that changed
     * @param action builds the action of the change from the bar
     */
    private void send(BossBar bar, Function<BossBar, BossBarPacket.Action> action) {
        final BossBarHolder holder = this.manager.bars.get(bar);
        if (holder == null) return;
        // The viewers of the holder only change under its monitor, see BossBarManager
        synchronized (holder) {
            PacketSendingUtils.sendGroupedPacket(holder.players, new BossBarPacket(holder.uuid, action.apply(bar)));
        }
    }
}
