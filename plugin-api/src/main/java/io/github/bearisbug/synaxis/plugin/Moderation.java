package io.github.bearisbug.synaxis.plugin;

import org.jetbrains.annotations.Nullable;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Bans and the whitelist, shared by every shard of every region ({@code docs/DESIGN-PROD.md}
 * {@code REQ-PROD-010}, API-PROD-009).
 * <p>
 * A player is checked while it logs in, after the Mojang verification and before the admission
 * provider: a ban in force turns it away with {@link DisconnectKeys#BANNED}, and with
 * {@code synaxis.app.whitelist=on} an account not on the whitelist is turned away with
 * {@link DisconnectKeys#NOT_WHITELISTED}. A ban also kicks the players it covers who are already
 * online, on every shard. A change reaches the other shards at once while Redis is up and within
 * 30 seconds while it is down.
 * <p>
 * Every method may be called from any thread, a tick thread included: the changes go to the
 * database on a virtual thread, and a future that fails carries an {@link IllegalStateException}.
 */
public interface Moderation {

    /**
     * Bans an account or a network. The players it covers who are online are kicked.
     *
     * @param target    who to ban; an account need not have logged in yet
     * @param reason    what the banned player is shown, 1 to 256 characters
     * @param expiresAt when the ban stops applying, null for never
     * @param operator  who bans, the nil UUID for the console
     * @return the ban, once it is in the database
     * @throws IllegalArgumentException if the reason is empty or longer than 256 characters, or the
     *                                  expiry is not in the future
     */
    CompletableFuture<Ban> ban(BanTarget target, String reason, @Nullable Instant expiresAt, UUID operator);

    /**
     * Lifts a ban.
     *
     * @param banId    the ban
     * @param operator who lifts it, the nil UUID for the console
     * @return true once it is lifted, false if there is no such ban in force
     */
    CompletableFuture<Boolean> unban(long banId, UUID operator);

    /**
     * The ban a player would be turned away with, as this shard knows it: its own changes at once,
     * the other shards' within the delay described above.
     *
     * @param uuid the account
     * @param ip   the address it connects from, null to look at the account alone
     * @return the ban in force that lasts longest, empty if none covers the player
     */
    Optional<Ban> activeBan(UUID uuid, @Nullable InetAddress ip);

    /**
     * Puts an account on the whitelist or takes it off. Whether the whitelist is enforced is the
     * operator's switch {@code synaxis.app.whitelist}; taking an online player off does not kick it.
     *
     * @param account the account, which need not have logged in yet
     * @param listed  true to put it on, false to take it off
     * @return completes once the whitelist is changed
     */
    CompletableFuture<Void> whitelist(UUID account, boolean listed);
}
