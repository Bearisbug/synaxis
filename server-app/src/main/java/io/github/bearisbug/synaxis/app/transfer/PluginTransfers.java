package io.github.bearisbug.synaxis.app.transfer;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.OutgoingTransferEvent;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.server.common.TransferPacket;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.plugin.Transfers;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The transfer capability of one plugin ({@code docs/DESIGN-PROD.md} {@code API-PROD-021}): vanilla
 * transfers on the cooldown table of the process, which the host's ticketed transfers use too, and
 * cookies in the plugin's own namespace.
 * <p>
 * A transfer is decided and sent on the tick thread that owns the player, at its next tick:
 * {@link OutgoingTransferEvent} is a player event, and its listeners may touch the player
 * ({@code API-PROD-016}).
 */
public final class PluginTransfers implements Transfers {

    /** How long a cookie request waits for the client, as long as a transfer's target waits for its ticket. */
    static final long COOKIE_TIMEOUT_MILLIS = 5_000;
    /** How long a transfer waits for the player's next tick; a player that does not tick in that time has left. */
    static final long TICK_TIMEOUT_MILLIS = 5_000;
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
    public CompletableFuture<Boolean> transfer(Player player, String host, int port) {
        if (host.isBlank()) throw new IllegalArgumentException("a transfer needs a host");
        if (port < 1 || port > MAX_PORT)
            throw new IllegalArgumentException("port " + port + " is not 1 to " + MAX_PORT);
        if (!inPlay(player)) return CompletableFuture.completedFuture(false);
        final CompletableFuture<Boolean> result = new CompletableFuture<>();
        // Whichever of the tick and the timeout comes first decides, the other one does nothing
        final AtomicBoolean decided = new AtomicBoolean();
        player.scheduler().scheduleNextTick(() -> {
            if (!decided.compareAndSet(false, true)) return;
            try {
                result.complete(send(player, host, port));
            } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
            }
        });
        // A player that left before its next tick never runs the task above
        CompletableFuture.delayedExecutor(TICK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS, Runnable::run).execute(() -> {
            if (decided.compareAndSet(false, true)) result.complete(false);
        });
        return result;
    }

    /**
     * The transfer itself, on the tick thread that owns the player. The host calls the event and
     * sends the packet rather than {@link PlayerConnection#transfer}, which does not tell whether a
     * listener cancelled it: the cooldown starts only for a packet that goes out.
     */
    private boolean send(Player player, String host, int port) {
        if (!inPlay(player) || cooldowns.coolingDown(player.getUuid())) return false;
        final PlayerConnection connection = player.getPlayerConnection();
        final OutgoingTransferEvent event = new OutgoingTransferEvent(player, host, port);
        connection.process().eventHandler().call(event);
        if (event.isCancelled()) return false;
        cooldowns.record(player.getUuid(), cooldowns.now());
        connection.sendPacket(new TransferPacket(event.getHost(), event.getPort()));
        return true;
    }

    /** Before the play state the host is still admitting the player, as the issuer's {@code NOT_ONLINE}. */
    private static boolean inPlay(Player player) {
        final PlayerConnection connection = player.getPlayerConnection();
        return connection.isOnline() && connection.getServerState() == ConnectionState.PLAY;
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
