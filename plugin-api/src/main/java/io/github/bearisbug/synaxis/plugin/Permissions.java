package io.github.bearisbug.synaxis.plugin;

import io.github.bearisbug.synaxis.entity.Player;
import org.jetbrains.annotations.ApiStatus;

import java.util.Set;

/**
 * What a player may do, by permission node ({@code docs/DESIGN-PROD.md} API-PROD-010).
 * <p>
 * A player holds roles, a role grants nodes. A node is lower case and dotted, such as
 * {@code gm.ban} or {@code synaxis.command.kick}; a granted node may end in {@code *}, so that
 * {@code gm.*} grants every node under {@code gm.} and a lone {@code *} grants every node.
 * <p>
 * The host reads a player's roles while the player logs in and reloads them when they change.
 * {@link Player#getPermissionLevel()} is not consulted: it only drives what the client shows.
 * Both methods may be called from any thread.
 * <p>
 * Only the host implements this interface: a plugin is handed one and calls it.
 */
@ApiStatus.NonExtendable
public interface Permissions {

    /**
     * Whether a player holds a node.
     *
     * @param player an online player
     * @param node   a node without {@code *}
     * @return true if one of the player's roles grants the node or a wildcard over it; false for a
     *         player whose roles are not loaded
     * @throws IllegalArgumentException if the node is not lower case and dotted, or holds {@code *}
     */
    boolean has(Player player, String node);

    /**
     * The roles of a player.
     *
     * @param player an online player
     * @return the role ids, empty for a player whose roles are not loaded
     */
    Set<String> roles(Player player);
}
