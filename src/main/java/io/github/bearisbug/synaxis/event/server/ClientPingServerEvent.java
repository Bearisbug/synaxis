package io.github.bearisbug.synaxis.event.server;

import io.github.bearisbug.synaxis.event.trait.AsyncEvent;
import io.github.bearisbug.synaxis.event.trait.CancellableEvent;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;

import java.time.Duration;
import java.util.Objects;


/**
 * Called when a {@link PlayerConnection} sends a ping packet,
 * usually after the status packet.
 *
 * @see ServerListPingEvent
 */
public class ClientPingServerEvent implements CancellableEvent, AsyncEvent {
    private final PlayerConnection connection;
    private long payload;

    private boolean cancelled = false;
    private Duration delay;

    /**
     * Creates a new client ping server event with 0 delay
     *
     * @param connection the player connection
     * @param payload    the payload the client sent
     */
    public ClientPingServerEvent(PlayerConnection connection, long payload) {
        this(connection, payload, Duration.ZERO);
    }

    /**
     * Creates a new client ping server event with the given delay
     *
     * @param connection the player connection
     * @param payload    the payload the client sent
     * @param delay      the delay until the engine sends the ping response packet
     */
    public ClientPingServerEvent(PlayerConnection connection, long payload, Duration delay) {
        this.connection = connection;
        this.payload = payload;
        this.delay = Objects.requireNonNull(delay, "delay");
    }

    /**
     * PlayerConnection of received packet. Note that the player has not joined the server
     * at this time.
     *
     * @return the connection.
     */
    public PlayerConnection getConnection() {
        return connection;
    }

    /**
     * Payload of received packet. May be any number; vanilla uses a system dependant time value.
     *
     * @return the payload
     */
    public long getPayload() {
        return payload;
    }

    /**
     * Sets the payload to respond with.
     *
     * @param payload the payload
     */
    public void setPayload(long payload) {
        this.payload = payload;
    }

    /**
     * Gets the delay until the engine will send the ping response packet.
     * <p>
     * Zero answers right away and closes the connection. Any other value keeps the connection open
     * meanwhile; once the delay ran out, the response is sent and the connection closed on the next
     * tick of the process.
     *
     * @return the delay
     */
    public Duration getDelay() {
        return delay;
    }

    /**
     * Adds to the delay until the engine will send the ping response packet.
     * <p>
     * A connection is answered once: a second ping on it closes it without any event.
     *
     * @param delay the delay
     */
    public void addDelay(Duration delay) {
        this.delay = this.delay.plus(Objects.requireNonNull(delay, "delay"));
    }

    /**
     * Sets the delay until the engine will send the ping response packet, see {@link #getDelay()}.
     *
     * @param delay the delay
     */
    public void setDelay(Duration delay) {
        this.delay = Objects.requireNonNull(delay, "delay");
    }

    /**
     * Clears the delay until the engine will send the ping response packet.
     */
    public void noDelay() {
        this.delay = Duration.ZERO;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Cancelling this event will cause the server to appear offline in the vanilla server list.
     *
     * @param cancel true if the event should be cancelled, false otherwise
     */
    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
}
