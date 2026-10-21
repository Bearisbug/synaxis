package io.github.bearisbug.synaxis.game.protocol;

/**
 * A payload that cannot be a message of this protocol: truncated, overlong, a count over its cap or
 * a field outside the range of {@code docs/DESIGN-M2.md} §9, of {@code docs/DESIGN-PLAY.md}
 * {@code API-PLAY-002} and {@code API-PLAY-003} for {@code synaxis:play}.
 * <p>
 * The compact constructor of each record throws it as well, on both ends, for a value outside those
 * ranges: decoding builds the record it read through the same constructor. Thrown while this end
 * builds a record to send, it says that the sending code put in a value out of range, which has
 * nothing to do with the peer. Only around the static {@code read} of a channel, where a payload of
 * the peer is decoded, is it the peer's fault: there the host disconnects with the
 * {@code synaxis:protocol-error} of §14. It is deliberately distinct from
 * {@link UnknownMessageException}, which is not an error at all — an unknown {@code msg_id} is
 * dropped with a warning and the connection stays.
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
