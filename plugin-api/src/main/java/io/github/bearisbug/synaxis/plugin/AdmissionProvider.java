package io.github.bearisbug.synaxis.plugin;

/**
 * Decides what a connecting player is let into (API-M2-011).
 * <p>
 * The host calls it inline on the configuration virtual thread of that connection, once per
 * connection, after the handshake and before the spawning instance is set. It must not block and
 * must not touch entities or chunks: the player is not in any instance yet. An exception is treated
 * as {@code Reject("synaxis:match-unavailable")}.
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
