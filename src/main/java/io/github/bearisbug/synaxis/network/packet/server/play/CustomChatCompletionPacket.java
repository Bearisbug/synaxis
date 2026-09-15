package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import java.util.List;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.STRING;

public record CustomChatCompletionPacket(Action action,
                                         List<String> entries) implements ServerPacket.Play {
    public static final int MAX_ENTRIES = Short.MAX_VALUE;

    public static final NetworkBuffer.Type<CustomChatCompletionPacket> SERIALIZER = NetworkBufferTemplate.template(
            NetworkBuffer.Enum(Action.class), CustomChatCompletionPacket::action,
            STRING.list(MAX_ENTRIES), CustomChatCompletionPacket::entries,
            CustomChatCompletionPacket::new);

    public CustomChatCompletionPacket {
        entries = List.copyOf(entries);
    }

    public enum Action {
        ADD, REMOVE, SET
    }
}
