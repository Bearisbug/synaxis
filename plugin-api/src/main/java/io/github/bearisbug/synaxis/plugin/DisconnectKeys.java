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

    private DisconnectKeys() {
    }
}
