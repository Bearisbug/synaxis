package io.github.bearisbug.synaxis.event.server;

import io.github.bearisbug.synaxis.event.trait.AsyncEvent;
import io.github.bearisbug.synaxis.network.Favicon;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Called once for the status request of a {@link PlayerConnection}, before the server list answer
 * is sent: whatever the listeners leave in here is what the client shows
 * ({@code docs/DESIGN-PROD.md} {@code API-PROD-024}).
 * <p>
 * It starts with what the process answers on its own: the configured MOTD, the players in play,
 * the real player limit, up to 12 of those players and the configured icon. It is called on the
 * read thread of the connection.
 * <p>
 * The client reads at most 32,767 characters of the answer and shows the server as unreachable
 * when it is longer. Nothing is cut short here, so a listener that sets a long description or
 * sample keeps it within that bound itself.
 *
 * @see ClientPingServerEvent
 */
public final class ServerListPingEvent implements AsyncEvent {

    /**
     * One line of the player sample, shown when the pointer is on the player count.
     *
     * @param name the text of the line
     * @param uuid the player it stands for, any UUID for a line that is not a player
     */
    public record SamplePlayer(String name, UUID uuid) {
        public SamplePlayer {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(uuid, "uuid");
        }
    }

    private final PlayerConnection connection;
    private final int playerLimit;
    private Component description;
    private int onlinePlayers;
    private int maxPlayers;
    private List<SamplePlayer> sample;
    private @Nullable Favicon favicon;

    /**
     * An event holding the answer the process would give without it.
     *
     * @param connection    the connection that asked
     * @param description   the MOTD
     * @param onlinePlayers the player count
     * @param playerLimit   the real player limit of the process, which {@link #setMaxPlayers} cannot go below
     * @param sample        the player sample
     * @param favicon       the icon, null for none
     */
    public ServerListPingEvent(PlayerConnection connection, Component description, int onlinePlayers, int playerLimit,
                               List<SamplePlayer> sample, @Nullable Favicon favicon) {
        this.connection = connection;
        this.playerLimit = playerLimit;
        this.description = Objects.requireNonNull(description, "description");
        this.onlinePlayers = onlinePlayers;
        this.maxPlayers = playerLimit;
        this.sample = List.copyOf(sample);
        this.favicon = favicon;
    }

    /**
     * The connection that asked. It is in the status state: no player exists for it.
     *
     * @return the connection
     */
    public PlayerConnection getConnection() {
        return connection;
    }

    public Component getDescription() {
        return description;
    }

    public void setDescription(Component description) {
        this.description = Objects.requireNonNull(description, "description");
    }

    public int getOnlinePlayers() {
        return onlinePlayers;
    }

    public void setOnlinePlayers(int onlinePlayers) {
        this.onlinePlayers = onlinePlayers;
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    /**
     * Sets the player limit shown. Load balancers read it, so it can be raised but never shown
     * below the real limit of the process.
     *
     * @param maxPlayers the limit to show
     * @throws IllegalArgumentException if {@code maxPlayers} is below the real limit; the value is not changed
     */
    public void setMaxPlayers(int maxPlayers) {
        if (maxPlayers < playerLimit)
            throw new IllegalArgumentException("the server list must not show a player limit below the real one, "
                    + playerLimit + ", but was given " + maxPlayers);
        this.maxPlayers = maxPlayers;
    }

    /**
     * The player sample, unmodifiable.
     *
     * @return the sample
     */
    public List<SamplePlayer> getSample() {
        return sample;
    }

    /**
     * Replaces the player sample; an empty one leaves the field out.
     *
     * @param sample the new sample, copied
     */
    public void setSample(List<SamplePlayer> sample) {
        this.sample = List.copyOf(sample);
    }

    public @Nullable Favicon getFavicon() {
        return favicon;
    }

    /**
     * Sets the icon.
     *
     * @param favicon the icon, null to send none
     */
    public void setFavicon(@Nullable Favicon favicon) {
        this.favicon = favicon;
    }
}
