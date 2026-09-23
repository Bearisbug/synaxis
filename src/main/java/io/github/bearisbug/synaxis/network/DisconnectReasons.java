package io.github.bearisbug.synaxis.network;

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
    public static final TranslatableComponent UNSUPPORTED_VERSION = Component.translatable("synaxis:unsupported-version");

    /**
     * The client sent an illegal frame, an oversized payload, or advanced the connection
     * state machine in a way the server did not allow.
     */
    public static final TranslatableComponent PROTOCOL_ERROR = Component.translatable("synaxis:protocol-error");

    /**
     * The connection did not reach the configuration state in time.
     */
    public static final TranslatableComponent LOGIN_TIMEOUT = Component.translatable("synaxis:login-timeout");

    /**
     * Another live connection already owns the profile uuid this one logged in with.
     */
    public static final TranslatableComponent DUPLICATE_LOGIN = Component.translatable("synaxis:duplicate-login");

    /**
     * The server failed to bring the client into the world, e.g. the spawn chunks could not be
     * loaded or the entity tracker refused the player.
     */
    public static final TranslatableComponent INTERNAL_ERROR = Component.translatable("synaxis:internal-error");

    /**
     * The server process is shutting down.
     */
    public static final TranslatableComponent SERVER_STOPPING = Component.translatable("synaxis:server-stopping");

    /**
     * The address of the connection already holds {@code synaxis.max-connections-per-ip}
     * connections (connection gate L1).
     */
    public static final TranslatableComponent TOO_MANY_CONNECTIONS = Component.translatable("synaxis:too-many-connections");

    /**
     * The address of the connection logged in or transferred in too often (connection gate L2).
     */
    public static final TranslatableComponent LOGIN_THROTTLED = Component.translatable("synaxis:login-throttled");

    /**
     * Every seat of {@code synaxis.max-players} is taken (connection gate L3).
     */
    public static final TranslatableComponent SHARD_FULL = Component.translatable("synaxis:shard-full");

    /**
     * The client did not answer a keep alive within {@code synaxis.keep-alive-kick}.
     */
    public static final TranslatableComponent KEEP_ALIVE_TIMEOUT = Component.translatable("synaxis:keep-alive-timeout");

    /**
     * The session server answered 204: the name did not join this server with a Mojang session.
     * Sent encrypted, the client switched to the cipher when it sent its key.
     */
    public static final TranslatableComponent AUTH_FAILED = Component.translatable("synaxis:auth-failed");

    /**
     * The session server did not answer in time, or answered 429, 5xx or garbage, and the one
     * retry failed too. Sent encrypted.
     */
    public static final TranslatableComponent AUTH_UNAVAILABLE = Component.translatable("synaxis:auth-unavailable");

    /**
     * Online mode: the same Mojang uuid logged in again, and this older session makes room for it.
     */
    public static final TranslatableComponent LOGGED_IN_ELSEWHERE = Component.translatable("synaxis:logged-in-elsewhere");

    /**
     * The player's state could not be brought in on time. From the engine: an online mode login
     * whose previous session did not leave the world within 5 seconds.
     */
    public static final TranslatableComponent STATE_UNAVAILABLE = Component.translatable("synaxis:state-unavailable");

    private DisconnectReasons() {
    }
}
