package io.github.bearisbug.synaxis.crypto;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;

import java.time.Instant;

public final class SignedMessageBody {
    // The chat limit of the vanilla client, in characters
    private static final int MAX_CONTENT_LENGTH = 256;

    public record Packed(String content, Instant timeStamp, long salt,
                         LastSeenMessages.Packed lastSeen) {
        public Packed {
            if (content.length() > MAX_CONTENT_LENGTH) {
                throw new IllegalArgumentException("Message length cannot be greater than " + MAX_CONTENT_LENGTH + ": " + content.length());
            }
        }

        public static final NetworkBuffer.Type<Packed> SERIALIZER = NetworkBufferTemplate.template(
                NetworkBuffer.STRING, Packed::content,
                NetworkBuffer.INSTANT_MS, Packed::timeStamp,
                NetworkBuffer.LONG, Packed::salt,
                LastSeenMessages.Packed.SERIALIZER, Packed::lastSeen,
                Packed::new
        );
    }
}
