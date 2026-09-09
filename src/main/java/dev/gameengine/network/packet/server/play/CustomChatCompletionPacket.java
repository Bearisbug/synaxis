package dev.gameengine.network.packet.server.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import java.util.List;

import static dev.gameengine.network.NetworkBuffer.STRING;

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
