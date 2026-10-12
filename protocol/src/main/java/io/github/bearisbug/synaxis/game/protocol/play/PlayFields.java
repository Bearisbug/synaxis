package io.github.bearisbug.synaxis.game.protocol.play;

import io.github.bearisbug.synaxis.game.protocol.MalformedMessageException;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;

import java.nio.charset.StandardCharsets;

/**
 * The field checks the records of this package share, so that the sender and the receiver judge a value by
 * the same rule.
 */
final class PlayFields {

    private PlayFields() {
    }

    /**
     * Checks a string against its byte budget.
     *
     * @param field    the field name, for the message
     * @param value    the value
     * @param minBytes the shortest encoding allowed
     * @param maxBytes the longest encoding allowed
     * @throws MalformedMessageException if the UTF-8 encoding is outside {@code [minBytes, maxBytes]}
     */
    static void checkString(String field, String value, int minBytes, int maxBytes) {
        final int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < minBytes || bytes > maxBytes) {
            throw new MalformedMessageException(
                    "%s is %d bytes, outside [%d, %d]".formatted(field, bytes, minBytes, maxBytes));
        }
    }

    /**
     * Checks that a value fits in an unsigned byte.
     *
     * @param field the field name, for the message
     * @param value the value
     * @throws MalformedMessageException if the value is outside {@code [0, 255]}
     */
    static void checkU8(String field, int value) {
        if ((value & ~0xFF) != 0) throw new MalformedMessageException(field + " is not a u8: " + value);
    }

    /**
     * Checks a {@code cooldown_ms}.
     *
     * @param cooldownMs the value
     * @throws MalformedMessageException if it is outside {@code [0, MAX_COOLDOWN_MS]}
     */
    static void checkCooldown(int cooldownMs) {
        if (cooldownMs < 0 || cooldownMs > PlayProtocol.MAX_COOLDOWN_MS) {
            throw new MalformedMessageException(
                    "cooldown_ms %d is outside [0, %d]".formatted(cooldownMs, PlayProtocol.MAX_COOLDOWN_MS));
        }
    }

    /**
     * Reads a bool u8, which has exactly two encodings.
     *
     * @param buf   the reading buffer
     * @param field the field name, for the message
     * @return the value
     * @throws MalformedMessageException if the byte is neither 0 nor 1
     */
    static boolean readBool(MsgBuf buf, String field) {
        final int value = buf.readUnsignedByte();
        return switch (value) {
            case 0 -> false;
            case 1 -> true;
            default -> throw new MalformedMessageException(field + " is neither 0 nor 1: " + value);
        };
    }
}
