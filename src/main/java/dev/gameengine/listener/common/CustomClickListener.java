package dev.gameengine.listener.common;

import net.kyori.adventure.nbt.BinaryTagTypes;
import dev.gameengine.MinecraftServer;
import dev.gameengine.entity.Player;
import dev.gameengine.event.EventDispatcher;
import dev.gameengine.event.player.PlayerConfigCustomClickEvent;
import dev.gameengine.event.player.PlayerCustomClickEvent;
import dev.gameengine.network.ConnectionState;
import dev.gameengine.network.packet.client.common.ClientCustomClickActionPacket;

public final class CustomClickListener {

    public static void listener(ClientCustomClickActionPacket listener, Player player) {
        MinecraftServer.getClickCallbackManager().consumeCustomClick(player, listener);
        var event = player.getPlayerConnection().getClientState() == ConnectionState.PLAY
                ? new PlayerCustomClickEvent(player, listener.key(), listener.payload().type() == BinaryTagTypes.END ? null : listener.payload())
                : new PlayerConfigCustomClickEvent(player, listener.key(), listener.payload().type() == BinaryTagTypes.END ? null : listener.payload());
        EventDispatcher.call(event);
    }

}
