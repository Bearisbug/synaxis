package io.github.bearisbug.synaxis.listener.common;

import net.kyori.adventure.nbt.BinaryTagTypes;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerConfigCustomClickEvent;
import io.github.bearisbug.synaxis.event.player.PlayerCustomClickEvent;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientCustomClickActionPacket;

public final class CustomClickListener {

    public static void listener(ClientCustomClickActionPacket listener, Player player) {
        player.process().clickCallbackManager().consumeCustomClick(player, listener);
        var event = player.getPlayerConnection().getClientState() == ConnectionState.PLAY
                ? new PlayerCustomClickEvent(player, listener.key(), listener.payload().type() == BinaryTagTypes.END ? null : listener.payload())
                : new PlayerConfigCustomClickEvent(player, listener.key(), listener.payload().type() == BinaryTagTypes.END ? null : listener.payload());
        player.process().eventHandler().call(event);
    }

}
