package io.github.bearisbug.synaxis.testing;

import io.github.bearisbug.synaxis.JvmFlags;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.adventure.EngineAdventure;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

final class TestConnectionImpl implements TestConnection {
    private final ServerProcess process;
    private final GameProfile gameProfile;
    private final PlayerConnectionImpl playerConnection;

    private final AtomicBoolean connected = new AtomicBoolean(false);

    private final List<IncomingCollector<?>> incomingTrackers = new CopyOnWriteArrayList<>();

    TestConnectionImpl(Env env, GameProfile gameProfile) {
        this.process = env.process();
        this.gameProfile = gameProfile;
        this.playerConnection = new PlayerConnectionImpl(process);
    }

    @Override
    public Player connect(Instance instance, Pos pos) {
        if (!connected.compareAndSet(false, true)) {
            throw new IllegalStateException("Already connected");
        }

        var player = process.connection().createPlayer(playerConnection, gameProfile);
        if (player == null) {
            throw new IllegalStateException("Another connection is already online with the uuid " + gameProfile.uuid());
        }
        player.eventNode().addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(instance);
            event.getPlayer().setRespawnPoint(pos);
        });

        // Force the player through the entirety of the login process manually
        CompletableFuture<Player> future = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try {
                // `isFirstConfig` is set to false in order to not block the thread
                // waiting for known packs.
                // The consequence is that registry packets cannot be listened to.
                process.connection().doConfiguration(player, false);
                process.connection().transitionConfigToPlay(player);
                future.complete(player);
            } catch (Throwable t) {
                // The login sequence has several throwing paths; reporting them to the waiting
                // caller is the only way out of the join below.
                future.completeExceptionally(t);
            }
        });
        future.join();
        playerConnection.setClientState(ConnectionState.PLAY);
        playerConnection.setServerState(ConnectionState.PLAY);
        process.connection().updateWaitingPlayers();
        return player;
    }

    @Override
    public <T extends ServerPacket> Collector<T> trackIncoming(Class<T> type) {
        var tracker = new IncomingCollector<>(type);
        this.incomingTrackers.add(tracker);
        return tracker;
    }

    final class PlayerConnectionImpl extends PlayerConnection {
        private boolean online = true;

        PlayerConnectionImpl(ServerProcess process) {
            super(process);
        }

        @Override
        public void sendPacket(SendablePacket packet) {
            final var serverPacket = this.extractPacket(packet);
            // A BufferedPacket holds encoded bytes and no packet object; the outgoing event skips it too
            if (serverPacket == null) return;
            for (var tracker : incomingTrackers) {
                if (tracker.type.isAssignableFrom(serverPacket.getClass())) tracker.add(serverPacket);
            }
        }

        private @Nullable ServerPacket extractPacket(final SendablePacket packet) {
            if (!(packet instanceof ServerPacket serverPacket))
                return SendablePacket.extractServerPacket(getServerState(), packet);

            final Player player = getPlayer();
            if (player == null) return serverPacket;

            if (JvmFlags.AUTOMATIC_COMPONENT_TRANSLATION && serverPacket instanceof ServerPacket.ComponentHolding) {
                serverPacket = ((ServerPacket.ComponentHolding) serverPacket).copyWithOperator(component ->
                        EngineAdventure.COMPONENT_TRANSLATOR.apply(component, Objects.requireNonNullElseGet(player.getLocale(), EngineAdventure::getDefaultLocale)));
            }

            return serverPacket;
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("localhost", 25565);
        }

        @Override
        public boolean isOnline() {
            return online;
        }

        @Override
        public void disconnect() {
            online = false;
        }
    }

    final class IncomingCollector<T extends ServerPacket> implements Collector<T> {
        private final Class<T> type;
        private final List<T> packets = new CopyOnWriteArrayList<>();

        public IncomingCollector(Class<T> type) {
            this.type = type;
        }

        void add(ServerPacket packet) {
            packets.add(type.cast(packet));
        }

        @Override
        public List<T> collect() {
            incomingTrackers.remove(this);
            return List.copyOf(packets);
        }
    }

    static final class TestPlayerImpl extends Player {
        public TestPlayerImpl(PlayerConnection playerConnection, GameProfile gameProfile) {
            super(playerConnection, gameProfile);
        }

        @Override
        public void sendChunk(Chunk chunk) {
            // Send immediately
            sendPacket(chunk.getFullDataPacket());
        }
    }
}
