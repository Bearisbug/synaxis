package io.github.bearisbug.synaxis.crypto;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SignedMessageBodyTest {

    @Test
    void aMessageIsAtMostTheVanillaChatLimit() {
        assertDoesNotThrow(() -> new SignedMessageBody.Packed("a".repeat(256), Instant.EPOCH, 0L, LastSeenMessages.Packed.EMPTY));
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new SignedMessageBody.Packed("a".repeat(257), Instant.EPOCH, 0L, LastSeenMessages.Packed.EMPTY));
        assertEquals("Message length cannot be greater than 256: 257", error.getMessage());
    }
}
