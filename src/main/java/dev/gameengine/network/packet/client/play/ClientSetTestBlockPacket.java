package dev.gameengine.network.packet.client.play;

import dev.gameengine.coordinate.Point;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;
import dev.gameengine.utils.validate.Check;

public record ClientSetTestBlockPacket(
        Point blockPosition,
        TestBlockMode mode,
        String message
) implements ClientPacket.Play {

    public static final NetworkBuffer.Type<ClientSetTestBlockPacket> SERIALIZER = NetworkBufferTemplate.template(
            NetworkBuffer.BLOCK_POSITION, ClientSetTestBlockPacket::blockPosition,
            TestBlockMode.NETWORK_TYPE, ClientSetTestBlockPacket::mode,
            NetworkBuffer.STRING, ClientSetTestBlockPacket::message,
            ClientSetTestBlockPacket::new);

    public ClientSetTestBlockPacket {
        Check.argCondition(message.length() > Short.MAX_VALUE, "Message length cannot be greater than Short.MAX_VALUE");
    }

    public enum TestBlockMode {
        START,
        LOG,
        FAIL,
        ACCEPT;

        public static final NetworkBuffer.Type<TestBlockMode> NETWORK_TYPE = NetworkBuffer.Enum(TestBlockMode.class);
    }
}
