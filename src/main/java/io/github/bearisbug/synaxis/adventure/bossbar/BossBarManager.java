package io.github.bearisbug.synaxis.adventure.bossbar;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.bossbar.BossBar;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.PacketSendingUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages all boss bars known to this engine instance. Although this class can be used
 * to show boss bars to players, it is preferable to use the boss bar methods in the
 * {@link Audience} class instead.
 *
 * <p>This implementation is heavily based on
 * <a href="https://github.com/VelocityPowered/Velocity">Velocity</a>'s boss bar
 * management system.</p>
 *
 * <p>A boss bar is changed on the thread of its owner while its viewers come and go on theirs. The holder of each
 * bar is a monitor: viewers are added and removed and their packets queued while holding it, so each player gets the
 * add of a bar before any update and no update after the removal. Lock order: holder, then the send queue of a
 * player or a bin of the bar map.</p>
 *
 * @see Audience#showBossBar(BossBar)
 * @see Audience#hideBossBar(BossBar)
 */
public class BossBarManager {
    @SuppressWarnings("this-escape") // deliberate self registration during construction
    private final BossBarListener listener = new BossBarListener(this);
    // By player object: a player that reconnects is a new object with the same UUID
    private final Map<Player, Set<BossBarHolder>> playerBars = new ConcurrentHashMap<>();
    final Map<BossBar, BossBarHolder> bars = new ConcurrentHashMap<>();

    /**
     * Adds the specified player to the boss bar's viewers and spawns the boss bar, registering the
     * boss bar if needed.
     *
     * @param player the intended viewer
     * @param bar    the boss bar to show
     */
    public void addBossBar(Player player, BossBar bar) {
        // A removed player has had its boss bars cleared, it would be kept reachable forever
        if (player.isRemoved()) return;
        while (true) {
            final BossBarHolder holder = this.holderFor(bar);
            synchronized (holder) {
                // Dropped by its last viewer since the lookup, the bar has a new holder
                if (this.bars.get(bar) != holder) continue;
                if (!holder.addViewer(player)) return;
                player.sendPacket(holder.createAddPacket());
            }
            this.addPlayer(player, holder);
            return;
        }
    }

    /**
     * Removes the specified player from the boss bar's viewers and despawns the boss bar.
     *
     * @param player the intended viewer
     * @param bar    the boss bar to hide
     */
    public void removeBossBar(Player player, BossBar bar) {
        final BossBarHolder holder = this.bars.get(bar);
        if (holder == null) return;
        synchronized (holder) {
            if (!holder.removeViewer(player)) return;
            player.sendPacket(holder.createRemovePacket());
        }
        this.removePlayer(player, holder);
    }

    /**
     * Adds the specified players to the boss bar's viewers and spawns the boss bar, registering the
     * boss bar if needed.
     *
     * @param players the players
     * @param bar     the boss bar
     */
    public void addBossBar(Collection<? extends Player> players, BossBar bar) {
        while (true) {
            final BossBarHolder holder = this.holderFor(bar);
            final List<? extends Player> addedPlayers;
            synchronized (holder) {
                if (this.bars.get(bar) != holder) continue;
                addedPlayers = players.stream()
                        .filter(player -> !player.isRemoved() && holder.addViewer(player))
                        .toList();
                if (addedPlayers.isEmpty()) return;
                PacketSendingUtils.sendGroupedPacket(addedPlayers, holder.createAddPacket());
            }
            for (Player player : addedPlayers) {
                this.addPlayer(player, holder);
            }
            return;
        }
    }

    /**
     * Removes the specified players from the boss bar's viewers and despawns the boss bar.
     *
     * @param players the intended viewers
     * @param bar     the boss bar to hide
     */
    public void removeBossBar(Collection<? extends Player> players, BossBar bar) {
        final BossBarHolder holder = this.bars.get(bar);
        if (holder == null) return;
        final List<? extends Player> removedPlayers;
        synchronized (holder) {
            removedPlayers = players.stream().filter(holder::removeViewer).toList();
            if (removedPlayers.isEmpty()) return;
            PacketSendingUtils.sendGroupedPacket(removedPlayers, holder.createRemovePacket());
        }
        for (Player player : removedPlayers) {
            this.removePlayer(player, holder);
        }
    }

    /**
     * Completely destroys a boss bar, removing it from all players.
     *
     * @param bossBar the boss bar
     */
    public void destroyBossBar(BossBar bossBar) {
        BossBarHolder holder = this.bars.remove(bossBar);
        if (holder != null) {
            bossBar.removeListener(this.listener);
            final List<Player> viewers;
            synchronized (holder) {
                // An update that looked the holder up before it was removed then finds no viewer
                viewers = List.copyOf(holder.players);
                holder.players.clear();
                PacketSendingUtils.sendGroupedPacket(viewers, holder.createRemovePacket());
            }
            for (Player player : viewers) {
                this.removePlayer(player, holder);
            }
        }
    }

    /**
     * Removes a player from all of their boss bars. Note that this method does not
     * send any removal packets to the player. It is meant to be used when a player is
     * disconnecting from the server.
     *
     * @param player the player
     */
    public void removeAllBossBars(Player player) {
        Set<BossBarHolder> holders = this.playerBars.remove(player);
        if (holders != null) {
            for (BossBarHolder holder : holders) {
                holder.removeViewer(player);
                this.dropIfUnused(holder);
            }
        }
    }

    /**
     * Gets a collection of all boss bars currently visible to a given player.
     *
     * @param player the player
     * @return the boss bars
     */
    public Collection<BossBar> getPlayerBossBars(Player player) {
        Set<BossBarHolder> holders = this.playerBars.get(player);
        return holders != null ?
                holders.stream().map(holder -> holder.bar).toList() : List.of();
    }

    /**
     * Gets all the players for whom the given boss bar is currently visible.
     *
     * @param bossBar the boss bar
     * @return the players
     */
    public Collection<? extends Player> getBossBarViewers(BossBar bossBar) {
        BossBarHolder holder = this.bars.get(bossBar);
        return holder != null ?
                Collections.unmodifiableCollection(holder.players) : List.of();
    }

    /**
     * Gets or creates the holder of this bar. It may be dropped right after, which a caller checks for under the
     * monitor of the holder.
     *
     * @param bar the bar
     * @return the holder
     */
    private BossBarHolder holderFor(BossBar bar) {
        return this.bars.computeIfAbsent(bar, bossBar -> {
            BossBarHolder holder = new BossBarHolder(bossBar);
            bossBar.addListener(this.listener);
            return holder;
        });
    }

    private void addPlayer(Player player, BossBarHolder holder) {
        this.playerBars.compute(player, (_, holders) -> {
            if (holders == null) {
                holders = ConcurrentHashMap.newKeySet();
            }

            holders.add(holder);
            return holders;
        });
    }

    private void removePlayer(Player player, BossBarHolder holder) {
        this.playerBars.computeIfPresent(player, (_, holders) -> {
            holders.remove(holder);
            return holders.isEmpty() ? null : holders;
        });
        this.dropIfUnused(holder);
    }

    /**
     * Forgets a holder once its last viewer is gone. Adventure boss bars are compared by identity,
     * so holders kept past their last viewer accumulate for the lifetime of the process.
     *
     * @param holder the holder to drop
     */
    private void dropIfUnused(BossBarHolder holder) {
        // A viewer is only added under this monitor and after checking that the holder is still the registered one,
        // so an empty holder dropped here never gains one
        synchronized (holder) {
            if (!holder.players.isEmpty()) return;
            this.bars.computeIfPresent(holder.bar, (bar, current) -> {
                if (current != holder) return current;
                bar.removeListener(this.listener);
                return null;
            });
        }
    }
}
