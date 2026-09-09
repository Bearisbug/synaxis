package dev.gameengine.listener;

import dev.gameengine.entity.Player;
import dev.gameengine.network.packet.client.play.ClientChunkBatchReceivedPacket;

public final class ChunkBatchListener {

    public static void batchReceivedListener(ClientChunkBatchReceivedPacket packet, Player player) {
        player.onChunkBatchReceived(packet.targetChunksPerTick());
    }
}
