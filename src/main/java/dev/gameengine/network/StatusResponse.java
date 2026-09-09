package dev.gameengine.network;

import dev.gameengine.MinecraftServer;

/**
 * Builds the server list JSON answered in the {@code status} connection state.
 * <p>
 * The engine does not model the vanilla server list: the answer is fixed apart from the
 * online player count, and exists so a vanilla client can complete
 * {@code handshake -> status -> disconnected} without being served any gameplay data.
 */
public final class StatusResponse {
    private static final String DESCRIPTION = "game-engine";

    /**
     * Builds the JSON payload of a status response.
     *
     * @return the server list JSON
     */
    public static String json() {
        final int online = MinecraftServer.getConnectionManager().getOnlinePlayerCount();
        return """
                {"version":{"name":"%s","protocol":%d},\
                "players":{"online":%d,"max":%d},\
                "description":{"text":"%s"}}"""
                .formatted(MinecraftServer.VERSION_NAME, MinecraftServer.PROTOCOL_VERSION,
                        online, online + 1, DESCRIPTION);
    }

    private StatusResponse() {
    }
}
