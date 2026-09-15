package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.ServerProcess;

/**
 * Builds the server list JSON answered in the {@code status} connection state.
 * <p>
 * The engine does not model the vanilla server list: the answer is fixed apart from the
 * online player count, and exists so a vanilla client can complete
 * {@code handshake -> status -> disconnected} without being served any gameplay data.
 */
public final class StatusResponse {
    private static final String DESCRIPTION = "synaxis";

    /**
     * Builds the JSON payload of a status response.
     *
     * @return the server list JSON
     */
    public static String json(ServerProcess process) {
        final int online = process.connection().getOnlinePlayerCount();
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
