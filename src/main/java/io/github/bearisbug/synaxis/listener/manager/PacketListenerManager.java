package io.github.bearisbug.synaxis.listener.manager;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.event.player.PlayerPacketEvent;
import io.github.bearisbug.synaxis.listener.AbilitiesListener;
import io.github.bearisbug.synaxis.listener.AnimationListener;
import io.github.bearisbug.synaxis.listener.AnvilListener;
import io.github.bearisbug.synaxis.listener.BlockPlacementListener;
import io.github.bearisbug.synaxis.listener.BookListener;
import io.github.bearisbug.synaxis.listener.ChatMessageListener;
import io.github.bearisbug.synaxis.listener.ChunkBatchListener;
import io.github.bearisbug.synaxis.listener.CreativeInventoryActionListener;
import io.github.bearisbug.synaxis.listener.EditSignListener;
import io.github.bearisbug.synaxis.listener.EntityActionListener;
import io.github.bearisbug.synaxis.listener.PlayPingListener;
import io.github.bearisbug.synaxis.listener.PlayStatusListener;
import io.github.bearisbug.synaxis.listener.PlayerActionListener;
import io.github.bearisbug.synaxis.listener.PlayerGameModeChangeListener;
import io.github.bearisbug.synaxis.listener.PlayerHeldListener;
import io.github.bearisbug.synaxis.listener.PlayerInputListener;
import io.github.bearisbug.synaxis.listener.PlayerLoadedListener;
import io.github.bearisbug.synaxis.listener.PlayerPickListener;
import io.github.bearisbug.synaxis.listener.PlayerPositionListener;
import io.github.bearisbug.synaxis.listener.PlayerSettingsMenuListener;
import io.github.bearisbug.synaxis.listener.PlayerSpectatorListener;
import io.github.bearisbug.synaxis.listener.PlayerTickListener;
import io.github.bearisbug.synaxis.listener.PlayerVehicleListener;
import io.github.bearisbug.synaxis.listener.TabCompleteListener;
import io.github.bearisbug.synaxis.listener.UseEntityListener;
import io.github.bearisbug.synaxis.listener.UseItemListener;
import io.github.bearisbug.synaxis.listener.WindowListener;
import io.github.bearisbug.synaxis.listener.common.CookieListener;
import io.github.bearisbug.synaxis.listener.common.CustomClickListener;
import io.github.bearisbug.synaxis.listener.common.KeepAliveListener;
import io.github.bearisbug.synaxis.listener.common.PluginMessageListener;
import io.github.bearisbug.synaxis.listener.common.ResourcePackListener;
import io.github.bearisbug.synaxis.listener.common.SettingsListener;
import io.github.bearisbug.synaxis.listener.preplay.HandshakeListener;
import io.github.bearisbug.synaxis.listener.preplay.LoginListener;
import io.github.bearisbug.synaxis.listener.preplay.StatusListener;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.PacketVanilla;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientCookieResponsePacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientCustomClickActionPacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientKeepAlivePacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientPingRequestPacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientPluginMessagePacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientPongPacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientResourcePackStatusPacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientSettingsPacket;
import io.github.bearisbug.synaxis.network.packet.client.configuration.ClientFinishConfigurationPacket;
import io.github.bearisbug.synaxis.network.packet.client.configuration.ClientSelectKnownPacksPacket;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginAcknowledgedPacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginPluginResponsePacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginStartPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientAnimationPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientAttackPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientChangeGameModePacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientChatMessagePacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientChatSessionUpdatePacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientChunkBatchReceivedPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientClickWindowButtonPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientClickWindowPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientCloseWindowPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientCommandChatPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientConfigurationAckPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientCreativeInventoryActionPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientEditBookPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientEntityActionPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientHeldItemChangePacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientInputPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientInteractEntityPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientNameItemPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPickItemFromBlockPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPickItemFromEntityPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerAbilitiesPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerActionPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerBlockPlacementPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerLoadedPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerPositionAndRotationPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerPositionPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerPositionStatusPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerRotationPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientSelectBundleItemPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientSetGameRulesPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientSetRecipeBookStatePacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientSignedCommandChatPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientSpectatorActionPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientStatusPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientTabCompletePacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientTeleportConfirmPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientTeleportToEntityPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientTickEndPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientUpdateSignPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientUseItemPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientVehicleMovePacket;
import io.github.bearisbug.synaxis.network.packet.client.status.StatusRequestPacket;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class PacketListenerManager {

    private final static Logger LOGGER = LoggerFactory.getLogger(PacketListenerManager.class);

    private final ServerProcess process;

    @SuppressWarnings({"unchecked", "rawtypes"}) // generic array creation
    private final Map<Class<? extends ClientPacket>, PacketPrePlayListenerConsumer>[] listeners = new Map[ConnectionState.values().length];
    private final Set<Class<?>> missingListenerWarnings = ConcurrentHashMap.newKeySet();

    public PacketListenerManager(ServerProcess process) {
        this.process = process;
        for (int i = 0; i < listeners.length; i++) {
            listeners[i] = new ConcurrentHashMap<>();
        }

        setListener(ConnectionState.HANDSHAKE, ClientHandshakePacket.class, HandshakeListener::listener);

        setListener(ConnectionState.STATUS, StatusRequestPacket.class, StatusListener::requestListener);
        setListener(ConnectionState.STATUS, ClientPingRequestPacket.class, StatusListener::pingRequestListener);

        setListener(ConnectionState.LOGIN, ClientLoginStartPacket.class, LoginListener::loginStartListener);
        setListener(ConnectionState.LOGIN, ClientLoginPluginResponsePacket.class, LoginListener::loginPluginResponseListener);
        setListener(ConnectionState.LOGIN, ClientLoginAcknowledgedPacket.class, LoginListener::loginAckListener);
        setListener(ConnectionState.LOGIN, ClientCookieResponsePacket.class, CookieListener::handleCookieResponse);

        setConfigurationListener(ClientSettingsPacket.class, SettingsListener::listener);
        setConfigurationListener(ClientPluginMessagePacket.class, PluginMessageListener::listener);
        setConfigurationListener(ClientKeepAlivePacket.class, KeepAliveListener::listener);
        setConfigurationListener(ClientPongPacket.class, (_, _) -> {/* empty */});
        setConfigurationListener(ClientResourcePackStatusPacket.class, ResourcePackListener::listener);
        setConfigurationListener(ClientSelectKnownPacksPacket.class, LoginListener::selectKnownPacks);
        setConfigurationListener(ClientFinishConfigurationPacket.class, LoginListener::finishConfigListener);
        setListener(ConnectionState.CONFIGURATION, ClientCookieResponsePacket.class, CookieListener::handleCookieResponse);
        setConfigurationListener(ClientCustomClickActionPacket.class, CustomClickListener::listener);

        setPlayListener(ClientKeepAlivePacket.class, KeepAliveListener::listener);
        setPlayListener(ClientCommandChatPacket.class, ChatMessageListener::commandChatListener);
        setPlayListener(ClientChatMessagePacket.class, ChatMessageListener::chatMessageListener);
        setPlayListener(ClientClickWindowPacket.class, WindowListener::clickWindowListener);
        setPlayListener(ClientCloseWindowPacket.class, WindowListener::closeWindowListener);
        setPlayListener(ClientClickWindowButtonPacket.class, WindowListener::inventoryButtonClickListener);
        setPlayListener(ClientConfigurationAckPacket.class, LoginListener::configAckListener);
        setPlayListener(ClientPongPacket.class, WindowListener::pong);
        setPlayListener(ClientEntityActionPacket.class, EntityActionListener::listener);
        setPlayListener(ClientHeldItemChangePacket.class, PlayerHeldListener::heldListener);
        setPlayListener(ClientPlayerBlockPlacementPacket.class, BlockPlacementListener::listener);
        setPlayListener(ClientInputPacket.class, PlayerInputListener::listener);
        setPlayListener(ClientChangeGameModePacket.class, PlayerGameModeChangeListener::listener);
        setPlayListener(ClientVehicleMovePacket.class, PlayerVehicleListener::vehicleMoveListener);
        setPlayListener(ClientPlayerPositionStatusPacket.class, PlayerPositionListener::playerPacketListener);
        setPlayListener(ClientPlayerRotationPacket.class, PlayerPositionListener::playerLookListener);
        setPlayListener(ClientPlayerPositionPacket.class, PlayerPositionListener::playerPositionListener);
        setPlayListener(ClientPlayerPositionAndRotationPacket.class, PlayerPositionListener::playerPositionAndLookListener);
        setPlayListener(ClientTeleportConfirmPacket.class, PlayerPositionListener::teleportConfirmListener);
        setPlayListener(ClientPlayerActionPacket.class, PlayerActionListener::playerActionListener);
        setPlayListener(ClientAnimationPacket.class, AnimationListener::animationListener);
        setPlayListener(ClientInteractEntityPacket.class, UseEntityListener::useEntityListener);
        setPlayListener(ClientAttackPacket.class, UseEntityListener::attackEntityListener);
        setPlayListener(ClientUseItemPacket.class, UseItemListener::useItemListener);
        setPlayListener(ClientPickItemFromBlockPacket.class, PlayerPickListener::playerPickBlockListener);
        setPlayListener(ClientPickItemFromEntityPacket.class, PlayerPickListener::playerPickEntityListener);
        setPlayListener(ClientStatusPacket.class, PlayStatusListener::listener);
        setPlayListener(ClientSettingsPacket.class, SettingsListener::listener);
        setPlayListener(ClientCreativeInventoryActionPacket.class, CreativeInventoryActionListener::listener);
        setPlayListener(ClientSetRecipeBookStatePacket.class, (_, _) -> {/* empty */});
        setPlayListener(ClientTabCompletePacket.class, TabCompleteListener::listener);
        setPlayListener(ClientPluginMessagePacket.class, PluginMessageListener::listener);
        setPlayListener(ClientPlayerAbilitiesPacket.class, AbilitiesListener::listener);
        setPlayListener(ClientResourcePackStatusPacket.class, ResourcePackListener::listener);
        setPlayListener(ClientSpectatorActionPacket.class, PlayerSpectatorListener::listener);
        setPlayListener(ClientTeleportToEntityPacket.class, PlayerSpectatorListener::listener);
        setPlayListener(ClientEditBookPacket.class, BookListener::listener);
        setPlayListener(ClientChatSessionUpdatePacket.class, (_, _) -> {/* empty */});
        setPlayListener(ClientChunkBatchReceivedPacket.class, ChunkBatchListener::batchReceivedListener);
        setPlayListener(ClientPingRequestPacket.class, PlayPingListener::requestListener);
        setListener(ConnectionState.PLAY, ClientCookieResponsePacket.class, CookieListener::handleCookieResponse);
        setPlayListener(ClientNameItemPacket.class, AnvilListener::nameItemListener);
        setPlayListener(ClientTickEndPacket.class, PlayerTickListener::listener);
        setPlayListener(ClientPlayerLoadedPacket.class, PlayerLoadedListener::listener);
        setPlayListener(ClientSelectBundleItemPacket.class, WindowListener::selectBundleItemListener);
        setPlayListener(ClientSignedCommandChatPacket.class, ChatMessageListener::signedCommandChatListener);
        setPlayListener(ClientCustomClickActionPacket.class, CustomClickListener::listener);
        setPlayListener(ClientUpdateSignPacket.class, EditSignListener::listener);
        setPlayListener(ClientSetGameRulesPacket.class, PlayerSettingsMenuListener::setGameRules);
    }

    /**
     * Processes a packet by getting its {@link PacketPlayListenerConsumer} and calling all the packet listeners.
     * <p>
     * Callers are responsible for handling any errors.
     *
     * @param packet     the received packet
     * @param connection the connection of the player who sent the packet
     * @param <T>        the packet type
     */
    public <T extends ClientPacket> void processClientPacket(T packet, PlayerConnection connection) {
        // Update connection state 'as we receive' the packet, aka before we send any responses
        // from processing. This is important for sending packets in response which are state-dependent.
        final ConnectionState currState = connection.getClientState();
        final ConnectionState nextState = PacketVanilla.nextClientState(packet, currState);
        if (nextState != currState) connection.setClientState(nextState);

        final Class<?> clazz = packet.getClass();
        @SuppressWarnings("unchecked")
        PacketPrePlayListenerConsumer<T> packetListenerConsumer = listeners[currState.ordinal()].get(clazz);

        // Listener can be null if none has been set before, call PacketConsumer anyway
        if (packetListenerConsumer == null) {
            // A registered packet without a listener is a static defect: warn once, otherwise a
            // client spamming such a packet turns it into a log flood.
            if (missingListenerWarnings.add(clazz))
                LOGGER.warn("Packet {}:{} does not have any default listener! (The issue likely comes from the engine)", clazz, currState);
            return;
        }

        // Event
        if (currState == ConnectionState.PLAY) {
            PlayerPacketEvent playerPacketEvent = new PlayerPacketEvent(connection.getPlayer(), packet);
            process.eventHandler().call(playerPacketEvent);
            if (playerPacketEvent.isCancelled()) {
                return;
            }
        }

        // Finally execute the listener
        packetListenerConsumer.accept(packet, connection); // possible throws
    }

    /**
     * Sets the listener of a packet.
     * <p>
     * WARNING: this will overwrite the default engine listener, this is not reversible.
     *
     * @param state       the state of the packet
     * @param packetClass the class of the packet
     * @param consumer    the new packet's listener
     * @param <T>         the type of the packet
     */
    public <T extends ClientPacket> void setListener(ConnectionState state, Class<T> packetClass, PacketPrePlayListenerConsumer<T> consumer) {
        this.listeners[state.ordinal()].put(packetClass, consumer);
    }

    /**
     * Sets the listener of a packet.
     * <p>
     * WARNING: this will overwrite the default engine listener, this is not reversible.
     *
     * @param packetClass the class of the packet
     * @param consumer    the new packet's listener
     * @param <T>         the type of the packet
     */
    public <T extends ClientPacket> void setPlayListener(Class<T> packetClass, PacketPlayListenerConsumer<T> consumer) {
        setListener(ConnectionState.PLAY, packetClass, (packet, playerConnection) -> consumer.accept(packet, playerConnection.getPlayer()));
    }

    public <T extends ClientPacket> void setConfigurationListener(Class<T> packetClass, PacketPlayListenerConsumer<T> consumer) {
        setListener(ConnectionState.CONFIGURATION, packetClass, (packet, playerConnection) -> consumer.accept(packet, playerConnection.getPlayer()));
    }

    /**
     * Sets the listener of a packet.
     * <p>
     * WARNING: this will overwrite the default engine listener, this is not reversible.
     *
     * @param packetClass the class of the packet
     * @param consumer    the new packet's listener
     * @param <T>         the type of the packet
     */
    @Deprecated
    public <T extends ClientPacket> void setListener(Class<T> packetClass, PacketPlayListenerConsumer<T> consumer) {
        setPlayListener(packetClass, consumer);
    }

}
