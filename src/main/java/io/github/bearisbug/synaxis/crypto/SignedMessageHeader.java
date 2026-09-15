package io.github.bearisbug.synaxis.crypto;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public record SignedMessageHeader(@Nullable MessageSignature previousSignature, UUID sender) {
    public static final NetworkBuffer.Type<SignedMessageHeader> SERIALIZER = NetworkBufferTemplate.template(
            MessageSignature.SERIALIZER.optional(), SignedMessageHeader::previousSignature,
            NetworkBuffer.UUID, SignedMessageHeader::sender,
            SignedMessageHeader::new
    );
}
