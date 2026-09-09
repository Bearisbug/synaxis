package dev.gameengine.network.packet;

import dev.gameengine.command.ArgumentParserType;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.packet.server.play.DeclareCommandsPacket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.gameengine.network.packet.server.play.DeclareCommandsPacket.getFlag;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class DeclareCommandsPacketTest {

    @Test
    void testWriteGameProfileArg() {
        var root = new DeclareCommandsPacket.Node();
        root.flags = getFlag(DeclareCommandsPacket.NodeType.ARGUMENT, false, false, false);
        root.parser = ArgumentParserType.GAME_PROFILE;
        var packet = new DeclareCommandsPacket(List.of(root), 0);

        var array = NetworkBuffer.makeArray(DeclareCommandsPacket.SERIALIZER, packet);
        var readPacket = NetworkBuffer.wrap(array, 0, array.length).read(DeclareCommandsPacket.SERIALIZER);
        assertEquals(ArgumentParserType.GAME_PROFILE, readPacket.nodes().getFirst().parser);
    }
}
