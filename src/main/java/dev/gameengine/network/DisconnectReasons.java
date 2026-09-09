package dev.gameengine.network;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;

/**
 * The disconnect reason codes of the engine.
 *
 * <p>The {@code translate} key of each component is the stable error code; it is what the client
 * shows on the disconnect screen and what the server logs next to the connection id.</p>
 */
public final class DisconnectReasons {

    /**
     * The client announced a protocol version the engine does not implement.
     */
    public static final TranslatableComponent UNSUPPORTED_VERSION = Component.translatable("engine:unsupported-version");

    /**
     * The client sent an illegal frame, an oversized payload, or advanced the connection
     * state machine in a way the server did not allow.
     */
    public static final TranslatableComponent PROTOCOL_ERROR = Component.translatable("engine:protocol-error");

    /**
     * The connection did not reach the configuration state in time.
     */
    public static final TranslatableComponent LOGIN_TIMEOUT = Component.translatable("engine:login-timeout");

    /**
     * The server failed to bring the client into the world, e.g. the spawn chunks could not be loaded.
     */
    public static final TranslatableComponent INTERNAL_ERROR = Component.translatable("engine:internal-error");

    /**
     * The server process is shutting down.
     */
    public static final TranslatableComponent SERVER_STOPPING = Component.translatable("engine:server-stopping");

    private DisconnectReasons() {
    }
}
