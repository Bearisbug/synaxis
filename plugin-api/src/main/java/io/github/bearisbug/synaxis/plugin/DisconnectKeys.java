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

    private DisconnectKeys() {
    }
}
