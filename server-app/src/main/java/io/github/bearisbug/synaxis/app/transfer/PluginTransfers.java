package io.github.bearisbug.synaxis.app.transfer;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.plugin.Transfers;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The transfer capability of one plugin ({@code docs/DESIGN-PROD.md} {@code API-PROD-021}): vanilla
 * transfers on the cooldown table of the process, which the host's ticketed transfers use too, and
 * cookies in the plugin's own namespace.
 */
public final class PluginTransfers implements Transfers {

    /** How long a cookie request waits for the client, as long as a transfer's target waits for its ticket. */
    static final long COOKIE_TIMEOUT_MILLIS = 5_000;
    private static final int MAX_PORT = 65_535;

    private final String pluginId;
    private final TransferCooldowns cooldowns;

    /**
     * @param pluginId  the plugin, whose id is the only cookie namespace it may use
     * @param cooldowns the cooldowns of the process, shared with its {@link TicketIssuer} and
     *                  {@link TicketVerifier}
     */
    public PluginTransfers(String pluginId, TransferCooldowns cooldowns) {
        this.pluginId = pluginId;
        this.cooldowns = cooldowns;
    }

    @Override
    public boolean transfer(Player player, String host, int port) {
        if (host.isBlank()) throw new IllegalArgumentException("a transfer needs a host");
        if (port < 1 || port > MAX_PORT)
            throw new IllegalArgumentException("port " + port + " is not 1 to " + MAX_PORT);
        final PlayerConnection connection = player.getPlayerConnection();
        // Before the play state the host is still admitting the player, as the issuer's NOT_ONLINE
        if (!connection.isOnline() || connection.getServerState() != ConnectionState.PLAY) return false;
        if (cooldowns.coolingDown(player.getUuid())) return false;
        cooldowns.record(player.getUuid(), cooldowns.now());
        connection.transfer(host, port);
        return true;
    }

    @Override
    public boolean wasTransferred(Player player) {
        return player.getPlayerConnection().wasTransferred();
    }

    @Override
    public void storeCookie(Player player, Key key, byte[] value) {
        // CookieStorePacket refuses a value over 5,120 bytes before anything is sent
        player.getPlayerConnection().storeCookie(own(key), value);
    }

    @Override
    public CompletableFuture<byte @Nullable []> fetchCookie(Player player, Key key) {
        return player.getPlayerConnection().fetchCookie(own(key))
                .orTimeout(COOKIE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    /**
     * The key of a cookie this plugin may touch. Keeping every plugin to its own namespace keeps it
     * off the host's ticket, {@link TransferTicket#COOKIE}: the engine holds one pending request per
     * key, so a plugin asking for the ticket would take the answer the target's check waits for.
     */
    private String own(Key key) {
        if (!key.namespace().equals(pluginId)) {
            throw new IllegalArgumentException("the cookie " + key.asString() + " is not in the namespace of plugin_id="
                    + pluginId);
        }
        return key.asString();
    }
}
