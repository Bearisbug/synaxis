package io.github.bearisbug.synaxis.protocol;

/**
 * A well formed envelope carrying a {@code msg_id} this channel does not define.
 * <p>
 * {@code docs/DESIGN-M2.md} §9 says such a packet is dropped and warned about, not punished, so the
 * decoder reports it apart from {@link MalformedMessageException}: the receiver keeps the
 * connection and only bounds how often it logs.
 */
@SuppressWarnings("serial") // never serialized
public final class UnknownMessageException extends RuntimeException {

    private final int msgId;

    /**
     * Creates the exception.
     *
     * @param channel the channel whose id space was searched, for the log line
     * @param msgId   the id that channel does not define
     */
    public UnknownMessageException(String channel, int msgId) {
        super("unknown msg_id 0x%02X on the %s channel".formatted(msgId, channel));
        this.msgId = msgId;
    }

    /**
     * The id that was not recognised; {@code msg_id} is scoped to one channel, so the same number
     * can be a valid message elsewhere.
     *
     * @return the unknown message id
     */
    public int msgId() {
        return msgId;
    }
}
