package io.github.bearisbug.synaxis.listener.preplay;

import io.github.bearisbug.synaxis.event.server.ClientPingServerEvent;
import io.github.bearisbug.synaxis.monitoring.EventsJFR;
import io.github.bearisbug.synaxis.network.StatusResponse;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientPingRequestPacket;
import io.github.bearisbug.synaxis.network.packet.client.status.StatusRequestPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.PingResponsePacket;
import io.github.bearisbug.synaxis.network.packet.server.status.ResponsePacket;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;

public final class StatusListener {

    public static void requestListener(StatusRequestPacket packet, PlayerConnection connection) {
        if (!connection.acquireStatusRequest()) {
            // Like vanilla, a connection only gets one status response
            connection.disconnect();
            return;
        }
        connection.sendPacket(new ResponsePacket(StatusResponse.json(connection.process())));
        EventsJFR.newServerPing(connection.getRemoteAddress().toString()).commit();
    }

    public static void pingRequestListener(ClientPingRequestPacket packet, PlayerConnection connection) {
        final ClientPingServerEvent clientPingEvent = new ClientPingServerEvent(connection, packet.number());
        connection.process().eventHandler().call(clientPingEvent);

        if (clientPingEvent.isCancelled()) {
            connection.disconnect();
        } else {
            if (clientPingEvent.getDelay().isZero()) {
                connection.sendPacket(new PingResponsePacket(clientPingEvent.getPayload()));
                connection.disconnect();
            } else {
                connection.process().scheduler().buildTask(() -> {
                    connection.sendPacket(new PingResponsePacket(clientPingEvent.getPayload()));
                    connection.disconnect();
                }).delay(clientPingEvent.getDelay()).schedule();
            }
        }
    }

}
