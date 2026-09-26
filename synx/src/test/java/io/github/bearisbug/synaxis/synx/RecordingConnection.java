package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.server.BufferedPacket;
import io.github.bearisbug.synaxis.network.packet.server.CachedPacket;
import io.github.bearisbug.synaxis.network.packet.server.FramedPacket;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A connection that keeps every {@link SendablePacket} in the form the player's sending path hands
 * it over, for a real {@link Player} (not the test player that sends chunks right away): the chunk
 * queue, the batches and {@code Chunk#getFullDataPacket()} are the production ones.
 */
final class RecordingConnection extends PlayerConnection {
    final List<SendablePacket> sent = new CopyOnWriteArrayList<>();

    RecordingConnection(ServerProcess process) {
        super(process);
    }

    /** Logs a real player in on {@code instance}, at {@code spawn}. */
    static Player connect(ServerProcess process, RecordingConnection connection, Instance instance, Pos spawn) {
        process.connection().setPlayerProvider(Player::new);
        final Player player = process.connection().createPlayer(connection,
                new GameProfile(UUID.randomUUID(), "Recorder"));
        if (player == null) throw new IllegalStateException("no player");
        player.eventNode().addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(instance);
            event.getPlayer().setRespawnPoint(spawn);
        });
        final CompletableFuture<Player> future = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try {
                process.connection().doConfiguration(player, false);
                process.connection().transitionConfigToPlay(player);
                future.complete(player);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        future.join();
        connection.setClientState(ConnectionState.PLAY);
        connection.setServerState(ConnectionState.PLAY);
        process.connection().updateWaitingPlayers();
        return player;
    }

    @Override
    public void sendPacket(SendablePacket packet) {
        sent.add(packet);
    }

    /** The packets sent as objects, cached or framed packets unwrapped. */
    <T extends ServerPacket> List<T> packets(Class<T> type) {
        return sent.stream().map(RecordingConnection::serverPacket).filter(type::isInstance).map(type::cast).toList();
    }

    List<BufferedPacket> buffered() {
        return sent.stream().filter(BufferedPacket.class::isInstance).map(BufferedPacket.class::cast).toList();
    }

    static @Nullable ServerPacket serverPacket(SendablePacket packet) {
        return switch (packet) {
            case ServerPacket serverPacket -> serverPacket;
            case CachedPacket cached -> cached.packet();
            case FramedPacket framed -> framed.packet();
            case BufferedPacket _ -> null;
        };
    }

    static byte[] bytes(BufferedPacket packet) {
        final byte[] bytes = new byte[(int) packet.length()];
        packet.buffer().copyTo(packet.index(), bytes, 0, bytes.length);
        return bytes;
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return new InetSocketAddress("localhost", 25565);
    }
}
