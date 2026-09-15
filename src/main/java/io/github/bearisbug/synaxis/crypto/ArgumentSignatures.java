package io.github.bearisbug.synaxis.crypto;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;

import java.util.List;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.STRING;

public record ArgumentSignatures(List<Entry> entries) {
    public static final int MAX_ENTRIES = 8;

    public ArgumentSignatures {
        entries = List.copyOf(entries);
    }

    public static final NetworkBuffer.Type<ArgumentSignatures> SERIALIZER = NetworkBufferTemplate.template(
            Entry.SERIALIZER.list(MAX_ENTRIES), ArgumentSignatures::entries,
            ArgumentSignatures::new
    );

    public record Entry(String name, MessageSignature signature) {
        public static final NetworkBuffer.Type<Entry> SERIALIZER = NetworkBufferTemplate.template(
                STRING, Entry::name,
                MessageSignature.SERIALIZER, Entry::signature,
                Entry::new
        );
    }
}
