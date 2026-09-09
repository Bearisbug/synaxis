package dev.gameengine.testing;

import dev.gameengine.coordinate.Pos;
import dev.gameengine.entity.Player;
import dev.gameengine.instance.Instance;
import dev.gameengine.network.packet.server.ServerPacket;

public interface TestConnection {
    Player connect(Instance instance, Pos pos);

    <T extends ServerPacket> Collector<T> trackIncoming(Class<T> type);

    default Collector<ServerPacket> trackIncoming() {
        return trackIncoming(ServerPacket.class);
    }
}
