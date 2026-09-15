package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.STRING;

public record ClientAdvancementTabPacket(Action action,
                                         @Nullable String tabIdentifier) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientAdvancementTabPacket> SERIALIZER = NetworkBuffer.Tagged(
            NetworkBuffer.Enum(Action.class), ClientAdvancementTabPacket::action,
            Map.of(
                    Action.OPENED_TAB, NetworkBufferTemplate.template(
                            STRING, ClientAdvancementTabPacket::tabIdentifier,
                            tabIdentifier -> new ClientAdvancementTabPacket(Action.OPENED_TAB, tabIdentifier))
            ),
            NetworkBufferTemplate.template(new ClientAdvancementTabPacket(Action.CLOSED_SCREEN, null))
    );

    public ClientAdvancementTabPacket {
        if (tabIdentifier != null && tabIdentifier.length() > 256) {
            throw new IllegalArgumentException("Tab identifier too long: " + tabIdentifier.length());
        }
    }

    public enum Action {
        OPENED_TAB, CLOSED_SCREEN
    }
}
