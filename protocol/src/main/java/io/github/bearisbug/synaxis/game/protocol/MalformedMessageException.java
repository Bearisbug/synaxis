package io.github.bearisbug.synaxis.game.protocol;

/**
 * A payload that cannot be a message of this protocol: truncated, overlong, a count over its cap or
 * a field outside the range of {@code docs/DESIGN-M2.md} §9.
 * <p>
 * The host maps it to the {@code synaxis:protocol-error} disconnect of §14. It is deliberately
 * distinct from {@link UnknownMessageException}, which is not an error at all — an unknown
 * {@code msg_id} is dropped with a warning and the connection stays.
 */
@SuppressWarnings("serial") // never serialized
public final class MalformedMessageException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message what about the payload made it undecodable
     */
    public MalformedMessageException(String message) {
        super(message);
    }
}
