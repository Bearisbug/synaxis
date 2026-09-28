package io.github.bearisbug.synaxis.plugin;

import io.github.bearisbug.synaxis.entity.Player;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * Vanilla transfers and cookies ({@code docs/DESIGN-PROD.md} {@code API-PROD-021}).
 * <p>
 * A transfer here carries no ticket: it sends the client to an external server, and the player
 * leaves this process the way a logout does. A shard only takes transfers that come with the
 * host's ticket, so a transfer to another shard is not something a plugin can start.
 * <p>
 * A cookie key has to be in this plugin's own namespace, {@code <plugin_id>:<path>}. The
 * {@code synaxis} namespace belongs to the host, which keeps the transfer ticket in
 * {@code synaxis:transfer}: a plugin can neither read nor overwrite it.
 * <p>
 * Every method may be called from any thread.
 * <p>
 * Only the host implements this interface: a plugin is handed one and calls it.
 */
@ApiStatus.NonExtendable
public interface Transfers {

    /**
     * Sends a player to another server with a vanilla transfer, without a ticket. The per player
     * transfer cooldown, {@code synaxis.app.transfer-cooldown-ms} (5 s by default), is the one the
     * host's ticketed transfers use: a player who transferred out or came in with a ticket within
     * it is refused here, and one sent here is refused by them.
     *
     * @param player the player, in the play state
     * @param host   where the client connects
     * @param port   the port it connects to, 1 to 65535
     * @return true if the transfer went to the client and the cooldown started (a listener of
     *         {@code OutgoingTransferEvent} can still cancel the packet); false, with nothing sent
     *         and no cooldown started, if the player is not in the play state (still configuring,
     *         or gone) or is still cooling down from its last transfer
     * @throws IllegalArgumentException if the host is blank or the port is out of range; nothing is
     *                                  sent
     */
    boolean transfer(Player player, String host, int port);

    /**
     * Whether the client said in its handshake that it was transferred here. It is the client's
     * claim, not a proof of where it came from; a process that does not take transfers turns such a
     * connection away before it has a player, so there it is always false.
     *
     * @param player the player
     * @return true if the player's connection has the transfer intent
     */
    boolean wasTransferred(Player player);

    /**
     * Stores a cookie on the client, which keeps it and carries it along a transfer.
     *
     * @param player the player
     * @param key    the cookie, in this plugin's namespace
     * @param value  at most 5,120 bytes
     * @throws IllegalArgumentException if the key is outside this plugin's namespace or the value is
     *                                  longer than 5,120 bytes; nothing is sent
     */
    void storeCookie(Player player, Key key, byte[] value);

    /**
     * Asks the client for a cookie. The future completes on the thread of the connection that reads
     * the answer, or on the JDK's timer thread when the answer is late.
     *
     * @param player the player
     * @param key    the cookie, in this plugin's namespace
     * @return the value, null if the client has no such cookie; completes exceptionally with a
     *         {@link java.util.concurrent.TimeoutException} when no answer comes within 5 s (the
     *         player left, or the same cookie of the same player was asked for again before the
     *         answer, which then answers the later request only)
     * @throws IllegalArgumentException if the key is outside this plugin's namespace; nothing is sent
     */
    CompletableFuture<byte @Nullable []> fetchCookie(Player player, Key key);
}
