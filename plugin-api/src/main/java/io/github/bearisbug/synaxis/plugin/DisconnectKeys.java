package io.github.bearisbug.synaxis.plugin;

/**
 * The game side disconnect reason keys of {@code docs/DESIGN-M2.md} §14.
 * <p>
 * They live here rather than in the engine's {@code DisconnectReasons} on purpose: {@code synaxis:}
 * is only the shared translate prefix, and an engine that knew about client mods or matches would
 * break the dependency direction of §6. A key is used as the {@code translate} key of the
 * disconnect component, which is also what the client shows and what the server logs.
 */
public final class DisconnectKeys {

    /**
     * There is no match the connection could be let into, either because no plugin registered an
     * admission provider or because the provider turned this connection away.
     */
    public static final String MATCH_UNAVAILABLE = "synaxis:match-unavailable";

    /**
     * The connection did not send a {@code C2S_HELLO} inside the handshake budget, which is what a
     * client without the mod looks like from here: nothing arrives and nothing ever will.
     */
    public static final String CLIENT_MOD_REQUIRED = "synaxis:client-mod-required";

    /**
     * The connection announced a {@code schema_version} other than the one this server speaks. The
     * wire protocol has no deprecation window — both ends ship in the same modpack — so anything
     * but equality is a refusal.
     */
    public static final String PROTOCOL_VERSION_MISMATCH = "synaxis:protocol-version-mismatch";

    /**
     * The connection kept a game channel above the flood threshold of {@code docs/DESIGN-M2.md} §15
     * for three seconds running. It is a gate against scripted floods, not a gameplay limit: going
     * over the per second budget of an order or a camera report is answered in band, and only this
     * one disconnects.
     */
    public static final String GAME_FLOOD = "synaxis:game-flood";

    /**
     * The player's data cannot be had right now: it failed to load, the shard lost the database,
     * or another live session holds the character and did not let go in time
     * ({@code docs/DESIGN-PROD.md} §14). Trying again after 30 seconds works once the cause is gone.
     */
    public static final String STATE_UNAVAILABLE = "synaxis:state-unavailable";

    /**
     * The transfer ticket the client came in with cannot be used: missing or not signed by us,
     * expired, meant for another shard or another account, already used, or naming a version the
     * character is not at ({@code docs/DESIGN-PROD.md} §14). The character was released before the
     * ticket was issued, so logging in to any shard gets it back.
     */
    public static final String TRANSFER_REJECTED = "synaxis:transfer-rejected";

    private DisconnectKeys() {
    }
}
