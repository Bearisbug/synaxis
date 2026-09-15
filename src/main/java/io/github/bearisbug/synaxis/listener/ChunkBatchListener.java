package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientChunkBatchReceivedPacket;

public final class ChunkBatchListener {

    public static void batchReceivedListener(ClientChunkBatchReceivedPacket packet, Player player) {
        player.onChunkBatchReceived(packet.targetChunksPerTick());
    }
}
