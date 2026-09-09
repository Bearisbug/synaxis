package dev.gameengine.network.packet.server.play;

import net.kyori.adventure.text.Component;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;
import org.jetbrains.annotations.Nullable;

import static dev.gameengine.network.NetworkBuffer.COMPONENT;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record DisguisedChatPacket(
        Component message,
        int type,
        Component senderName,
        @Nullable Component targetName
) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<DisguisedChatPacket> SERIALIZER = NetworkBufferTemplate.template(
            COMPONENT, DisguisedChatPacket::message,
            VAR_INT, DisguisedChatPacket::type,
            COMPONENT, DisguisedChatPacket::senderName,
            COMPONENT.optional(), DisguisedChatPacket::targetName,
            DisguisedChatPacket::new);
}
