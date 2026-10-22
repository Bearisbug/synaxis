package io.github.bearisbug.synaxis.plugin;

/**
 * Decides what a connecting player is let into (API-M2-011).
 * <p>
 * The host calls it inline on the configuration virtual thread of that connection, once per
 * connection, after the handshake and before the spawning instance is set. It must not block and
 * must not touch entities or chunks: the player is not in any instance yet. An exception is treated
 * as {@code Reject("synaxis:match-unavailable")}; returning null, or building an answer its
 * constructor refuses, is treated the same way.
 * <p>
 * A provider that keeps an entry per {@link AdmissionRequest#connectionId()} checks that the
 * connection is still up and makes the entry in one critical section, and its
 * {@code PlayerDisconnectEvent} listener removes the entry under the same lock ({@code docs/DESIGN-M2.md}
 * {@code API-M2-011}). The check is {@code ConnectionManager#isConnected(connectionId)} of
 * {@code PluginContext#process()}, which turns false before the disconnect event is called: a
 * connection that drops between the handshake and this call is then either skipped here or cleaned
 * up by its disconnect, while an entry made without the check would never be taken away. The host
 * may still turn a connection away after an {@code Accept}, with {@code synaxis:state-unavailable}
 * once the shutdown save has started, so whatever this call sets up is taken back on the disconnect
 * event too.
 * <p>
 * The call has a soft budget of 200 ms, measured once it returned: a slower one is written as one
 * WARN line, and its answer stands.
 */
@FunctionalInterface
public interface AdmissionProvider {

    /**
     * Decides one admission.
     *
     * @param request the connection asking to be let in
     * @return the decision
     */
    Admission admit(AdmissionRequest request);
}
