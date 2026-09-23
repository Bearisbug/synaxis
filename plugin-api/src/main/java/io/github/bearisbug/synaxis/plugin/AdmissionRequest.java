package io.github.bearisbug.synaxis.plugin;

import io.github.bearisbug.synaxis.network.player.GameProfile;

import java.net.InetAddress;
import java.util.UUID;

/**
 * What the host knows about a connection when it asks for an admission decision (API-M2-011).
 *
 * @param connectionId  the engine connection id, the same one that appears in the server logs
 * @param username      the name the player logged in with
 * @param modVersion    the client mod version reported by the handshake
 * @param schemaVersion the wire schema version reported by the handshake
 * @param uuid          the account of the player, the Mojang UUID in online mode
 *                      ({@code docs/DESIGN-PROD.md} API-PROD-009)
 * @param remoteAddress the IP address the player connects from, the one a PROXY protocol header
 *                      named when the shard sits behind a load balancer (API-PROD-009)
 */
public record AdmissionRequest(UUID connectionId, String username, int modVersion, int schemaVersion,
                               UUID uuid, InetAddress remoteAddress) {

    /**
     * A request without the account and the address, the form a plugin built by hand for its tests
     * before they were part of it: the account is the offline UUID of the name and the address is
     * the loopback address.
     *
     * @param connectionId  the engine connection id
     * @param username      the name the player logged in with
     * @param modVersion    the client mod version reported by the handshake
     * @param schemaVersion the wire schema version reported by the handshake
     * @deprecated name the account and the address with the canonical constructor
     */
    @Deprecated
    public AdmissionRequest(UUID connectionId, String username, int modVersion, int schemaVersion) {
        this(connectionId, username, modVersion, schemaVersion, GameProfile.offlineUuid(username),
                InetAddress.getLoopbackAddress());
    }
}
