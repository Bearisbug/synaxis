package io.github.bearisbug.synaxis.listener.common;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerConfigCustomClickEvent;
import io.github.bearisbug.synaxis.event.player.PlayerCustomClickEvent;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientCustomClickActionPacket;
import org.jetbrains.annotations.Nullable;

public final class CustomClickListener {

    public static void listener(ClientCustomClickActionPacket packet, Player player) {
        player.process().clickCallbackManager().consumeCustomClick(player, packet);
        final @Nullable BinaryTag payload = packet.payload().type() == BinaryTagTypes.END ? null : packet.payload();
        var event = player.getPlayerConnection().getClientState() == ConnectionState.PLAY
                ? new PlayerCustomClickEvent(player, packet.key(), payload)
                : new PlayerConfigCustomClickEvent(player, packet.key(), payload);
        player.process().eventHandler().call(event);
    }

}
