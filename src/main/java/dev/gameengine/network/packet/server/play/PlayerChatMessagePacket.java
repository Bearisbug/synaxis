package dev.gameengine.network.packet.server.play;

import net.kyori.adventure.text.Component;
import dev.gameengine.crypto.FilterMask;
import dev.gameengine.crypto.MessageSignature;
import dev.gameengine.crypto.SignedMessageBody;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static dev.gameengine.network.NetworkBuffer.COMPONENT;
import static dev.gameengine.network.NetworkBuffer.UUID;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;

/**
 * Represents an outgoing chat message packet.
 */
public record PlayerChatMessagePacket(int globalIndex, UUID sender, int index, @Nullable MessageSignature signature,
                                      SignedMessageBody.Packed messageBody,
                                      @Nullable Component unsignedContent, FilterMask filterMask,
                                      int msgTypeId, Component msgTypeName,
                                      @Nullable Component msgTypeTarget) implements ServerPacket.Play, ServerPacket.ComponentHolding {

    public static final NetworkBuffer.Type<PlayerChatMessagePacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, PlayerChatMessagePacket::globalIndex,
            UUID, PlayerChatMessagePacket::sender,
            VAR_INT, PlayerChatMessagePacket::index,
            MessageSignature.SERIALIZER.optional(), PlayerChatMessagePacket::signature,
            SignedMessageBody.Packed.SERIALIZER, PlayerChatMessagePacket::messageBody,
            COMPONENT.optional(), PlayerChatMessagePacket::unsignedContent,
            FilterMask.SERIALIZER, PlayerChatMessagePacket::filterMask,
            VAR_INT, PlayerChatMessagePacket::msgTypeId,
            COMPONENT, PlayerChatMessagePacket::msgTypeName,
            COMPONENT.optional(), PlayerChatMessagePacket::msgTypeTarget,
            PlayerChatMessagePacket::new
    );

    @Override
    public List<Component> components() {
        final ArrayList<Component> list = new ArrayList<>();
        list.add(msgTypeName);
        if (unsignedContent != null) list.add(unsignedContent);
        if (msgTypeTarget != null) list.add(msgTypeTarget);
        return List.copyOf(list);
    }

    @Override
    public ServerPacket copyWithOperator(UnaryOperator<Component> operator) {
        return new PlayerChatMessagePacket(globalIndex, sender, index, signature,
                messageBody, operator.apply(unsignedContent), filterMask,
                msgTypeId, operator.apply(msgTypeName), operator.apply(msgTypeTarget));
    }
}
