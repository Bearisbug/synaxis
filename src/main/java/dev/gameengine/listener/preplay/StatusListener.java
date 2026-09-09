package dev.gameengine.listener.preplay;

import dev.gameengine.MinecraftServer;
import dev.gameengine.event.EventDispatcher;
import dev.gameengine.event.server.ClientPingServerEvent;
import dev.gameengine.monitoring.EventsJFR;
import dev.gameengine.network.StatusResponse;
import dev.gameengine.network.packet.client.common.ClientPingRequestPacket;
import dev.gameengine.network.packet.client.status.StatusRequestPacket;
import dev.gameengine.network.packet.server.common.PingResponsePacket;
import dev.gameengine.network.packet.server.status.ResponsePacket;
import dev.gameengine.network.player.PlayerConnection;

public final class StatusListener {

    public static void requestListener(StatusRequestPacket packet, PlayerConnection connection) {
        if (!connection.acquireStatusRequest()) {
            // Like vanilla, a connection only gets one status response
            connection.disconnect();
            return;
        }
        connection.sendPacket(new ResponsePacket(StatusResponse.json()));
        EventsJFR.newServerPing(connection.getRemoteAddress().toString()).commit();
    }

    public static void pingRequestListener(ClientPingRequestPacket packet, PlayerConnection connection) {
        final ClientPingServerEvent clientPingEvent = new ClientPingServerEvent(connection, packet.number());
        EventDispatcher.call(clientPingEvent);

        if (clientPingEvent.isCancelled()) {
            connection.disconnect();
        } else {
            if (clientPingEvent.getDelay().isZero()) {
                connection.sendPacket(new PingResponsePacket(clientPingEvent.getPayload()));
                connection.disconnect();
            } else {
                MinecraftServer.getSchedulerManager().buildTask(() -> {
                    connection.sendPacket(new PingResponsePacket(clientPingEvent.getPayload()));
                    connection.disconnect();
                }).delay(clientPingEvent.getDelay()).schedule();
            }
        }
    }

}
