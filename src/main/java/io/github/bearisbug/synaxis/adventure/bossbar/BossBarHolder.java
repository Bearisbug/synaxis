package io.github.bearisbug.synaxis.adventure.bossbar;

import net.kyori.adventure.bossbar.BossBar;
import io.github.bearisbug.synaxis.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * A holder of a boss bar. This class is not intended for public use, instead you should
 * use {@link BossBarManager} to manage boss bars for players.
 */
final class BossBarHolder {
    final UUID uuid = UUID.randomUUID();
    final Set<Player> players = new CopyOnWriteArraySet<>();
    final BossBar bar;

    BossBarHolder(BossBar bar) {
        this.bar = bar;
    }
}
