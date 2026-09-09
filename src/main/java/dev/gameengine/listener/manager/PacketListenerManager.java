package dev.gameengine.listener.manager;

import dev.gameengine.ServerProcess;
import dev.gameengine.event.player.PlayerPacketEvent;
import dev.gameengine.listener.AbilitiesListener;
import dev.gameengine.listener.AnimationListener;
import dev.gameengine.listener.AnvilListener;
import dev.gameengine.listener.BlockPlacementListener;
import dev.gameengine.listener.BookListener;
import dev.gameengine.listener.ChatMessageListener;
import dev.gameengine.listener.ChunkBatchListener;
import dev.gameengine.listener.CreativeInventoryActionListener;
import dev.gameengine.listener.EditSignListener;
import dev.gameengine.listener.EntityActionListener;
import dev.gameengine.listener.PlayPingListener;
import dev.gameengine.listener.PlayStatusListener;
import dev.gameengine.listener.PlayerActionListener;
import dev.gameengine.listener.PlayerGameModeChangeListener;
import dev.gameengine.listener.PlayerHeldListener;
import dev.gameengine.listener.PlayerInputListener;
import dev.gameengine.listener.PlayerLoadedListener;
import dev.gameengine.listener.PlayerPickListener;
import dev.gameengine.listener.PlayerPositionListener;
import dev.gameengine.listener.PlayerSettingsMenuListener;
import dev.gameengine.listener.PlayerSpectatorListener;
import dev.gameengine.listener.PlayerTickListener;
import dev.gameengine.listener.PlayerVehicleListener;
import dev.gameengine.listener.TabCompleteListener;
import dev.gameengine.listener.UseEntityListener;
import dev.gameengine.listener.UseItemListener;
import dev.gameengine.listener.WindowListener;
import dev.gameengine.listener.common.CookieListener;
import dev.gameengine.listener.common.CustomClickListener;
import dev.gameengine.listener.common.KeepAliveListener;
import dev.gameengine.listener.common.PluginMessageListener;
import dev.gameengine.listener.common.ResourcePackListener;
import dev.gameengine.listener.common.SettingsListener;
import dev.gameengine.listener.preplay.HandshakeListener;
import dev.gameengine.listener.preplay.LoginListener;
import dev.gameengine.listener.preplay.StatusListener;
import dev.gameengine.network.ConnectionState;
import dev.gameengine.network.packet.PacketVanilla;
import dev.gameengine.network.packet.client.ClientPacket;
import dev.gameengine.network.packet.client.common.ClientCookieResponsePacket;
import dev.gameengine.network.packet.client.common.ClientCustomClickActionPacket;
import dev.gameengine.network.packet.client.common.ClientKeepAlivePacket;
import dev.gameengine.network.packet.client.common.ClientPingRequestPacket;
import dev.gameengine.network.packet.client.common.ClientPluginMessagePacket;
import dev.gameengine.network.packet.client.common.ClientPongPacket;
import dev.gameengine.network.packet.client.common.ClientResourcePackStatusPacket;
import dev.gameengine.network.packet.client.common.ClientSettingsPacket;
import dev.gameengine.network.packet.client.configuration.ClientFinishConfigurationPacket;
import dev.gameengine.network.packet.client.configuration.ClientSelectKnownPacksPacket;
import dev.gameengine.network.packet.client.handshake.ClientHandshakePacket;
import dev.gameengine.network.packet.client.login.ClientLoginAcknowledgedPacket;
import dev.gameengine.network.packet.client.login.ClientLoginPluginResponsePacket;
import dev.gameengine.network.packet.client.login.ClientLoginStartPacket;
import dev.gameengine.network.packet.client.play.ClientAnimationPacket;
import dev.gameengine.network.packet.client.play.ClientAttackPacket;
import dev.gameengine.network.packet.client.play.ClientChangeGameModePacket;
import dev.gameengine.network.packet.client.play.ClientChatMessagePacket;
import dev.gameengine.network.packet.client.play.ClientChatSessionUpdatePacket;
import dev.gameengine.network.packet.client.play.ClientChunkBatchReceivedPacket;
import dev.gameengine.network.packet.client.play.ClientClickWindowButtonPacket;
import dev.gameengine.network.packet.client.play.ClientClickWindowPacket;
import dev.gameengine.network.packet.client.play.ClientCloseWindowPacket;
import dev.gameengine.network.packet.client.play.ClientCommandChatPacket;
import dev.gameengine.network.packet.client.play.ClientConfigurationAckPacket;
import dev.gameengine.network.packet.client.play.ClientCreativeInventoryActionPacket;
import dev.gameengine.network.packet.client.play.ClientEditBookPacket;
import dev.gameengine.network.packet.client.play.ClientEntityActionPacket;
import dev.gameengine.network.packet.client.play.ClientHeldItemChangePacket;
import dev.gameengine.network.packet.client.play.ClientInputPacket;
import dev.gameengine.network.packet.client.play.ClientInteractEntityPacket;
import dev.gameengine.network.packet.client.play.ClientNameItemPacket;
import dev.gameengine.network.packet.client.play.ClientPickItemFromBlockPacket;
import dev.gameengine.network.packet.client.play.ClientPickItemFromEntityPacket;
import dev.gameengine.network.packet.client.play.ClientPlayerAbilitiesPacket;
import dev.gameengine.network.packet.client.play.ClientPlayerActionPacket;
import dev.gameengine.network.packet.client.play.ClientPlayerBlockPlacementPacket;
import dev.gameengine.network.packet.client.play.ClientPlayerLoadedPacket;
import dev.gameengine.network.packet.client.play.ClientPlayerPositionAndRotationPacket;
import dev.gameengine.network.packet.client.play.ClientPlayerPositionPacket;
import dev.gameengine.network.packet.client.play.ClientPlayerPositionStatusPacket;
import dev.gameengine.network.packet.client.play.ClientPlayerRotationPacket;
import dev.gameengine.network.packet.client.play.ClientSelectBundleItemPacket;
import dev.gameengine.network.packet.client.play.ClientSetGameRulesPacket;
import dev.gameengine.network.packet.client.play.ClientSetRecipeBookStatePacket;
import dev.gameengine.network.packet.client.play.ClientSignedCommandChatPacket;
import dev.gameengine.network.packet.client.play.ClientSpectatorActionPacket;
import dev.gameengine.network.packet.client.play.ClientStatusPacket;
import dev.gameengine.network.packet.client.play.ClientTabCompletePacket;
import dev.gameengine.network.packet.client.play.ClientTeleportConfirmPacket;
import dev.gameengine.network.packet.client.play.ClientTeleportToEntityPacket;
import dev.gameengine.network.packet.client.play.ClientTickEndPacket;
import dev.gameengine.network.packet.client.play.ClientUpdateSignPacket;
import dev.gameengine.network.packet.client.play.ClientUseItemPacket;
import dev.gameengine.network.packet.client.play.ClientVehicleMovePacket;
import dev.gameengine.network.packet.client.status.StatusRequestPacket;
import dev.gameengine.network.player.PlayerConnection;
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
