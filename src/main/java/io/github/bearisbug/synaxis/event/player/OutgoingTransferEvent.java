package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.CancellableEvent;
import io.github.bearisbug.synaxis.event.trait.PlayerEvent;

import java.util.Objects;

/**
 * Called when a {@link Player} is about to be redirected to another server.
 * <br>
 * It can be canceled to prevent the transfer from occurring.
 */
public class OutgoingTransferEvent implements PlayerEvent, CancellableEvent {
    private final Player player;
    private String host;
    private int port;
    private boolean cancelled;

    /**
     * Creates the event of a transfer to {@code host} and {@code port}.
     *
     * @param player the player to transfer
     * @param host   the address of the target server, not blank
     * @param port   the port of the target server, from 1 to 65535
     * @throws IllegalArgumentException if {@code host} is blank or {@code port} is out of range
     */
    public OutgoingTransferEvent(Player player, String host, int port) {
        this.player = Objects.requireNonNull(player);
        this.host = checkHost(host);
        this.port = checkPort(port);
    }

    @Override
    public boolean isCancelled() {
        return this.cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public Player getPlayer() {
        return this.player;
    }

    /**
     * Returns the address of the target server that the player will be transferred to.
     *
     * @return the target host, usually an IP or domain name
     */
    public String getHost() {
        return this.host;
    }

    /**
     * Returns the port of the target server that the player will be transferred to.
     *
     * @return the target port
     */
    public int getPort() {
        return this.port;
    }

    /**
     * Changes the address of the target server that the player will be transferred to.
     *
     * @param host the address of the target server, usually an IP or domain name, not blank
     * @throws IllegalArgumentException if {@code host} is blank
     */
    public void setHost(String host) {
        this.host = checkHost(host);
    }

    /**
     * Changes the port of the target server that the player will be transferred to.
     *
     * @param port the target port, from 1 to 65535
     * @throws IllegalArgumentException if {@code port} is out of range
     */
    public void setPort(int port) {
        this.port = checkPort(port);
    }

    private static String checkHost(String host) {
        if (Objects.requireNonNull(host).isBlank()) throw new IllegalArgumentException("host must not be blank");
        return host;
    }

    private static int checkPort(int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port must be in 1..65535, got " + port);
        return port;
    }
}
