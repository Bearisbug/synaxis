package io.github.bearisbug.synaxis.plugin;

import java.util.UUID;

/**
 * What the host knows about a connection when it asks for an admission decision (API-M2-011).
 *
 * @param connectionId  the engine connection id, the same one that appears in the server logs
 * @param username      the name the player logged in with
 * @param modVersion    the client mod version reported by the handshake
 * @param schemaVersion the wire schema version reported by the handshake
 */
public record AdmissionRequest(UUID connectionId, String username, int modVersion, int schemaVersion) {
}
