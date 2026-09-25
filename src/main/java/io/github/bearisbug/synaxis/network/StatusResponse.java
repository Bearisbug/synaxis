package io.github.bearisbug.synaxis.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.server.ServerListPingEvent;
import io.github.bearisbug.synaxis.event.server.ServerListPingEvent.SamplePlayer;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds the server list JSON answered in the {@code status} connection state
 * ({@code docs/DESIGN-PROD.md} {@code API-PROD-024}).
 * <p>
 * The process answers with its configured MOTD and icon, the players in play, its real player
 * limit and a sample of up to 12 of those players; a {@link ServerListPingEvent} listener may
 * change any of it before it is written.
 */
public final class StatusResponse {
    /** The most players the sample lists, as many as vanilla does. */
    private static final int SAMPLE_SIZE = 12;
    /** What stands for a player who does not want to be listed, the vanilla entry. */
    private static final SamplePlayer ANONYMOUS = new SamplePlayer("Anonymous Player", new UUID(0, 0));

    /**
     * The event of one status request, holding what the process answers when no listener changes it.
     *
     * @param connection the connection that asked
     * @return the event to call
     */
    public static ServerListPingEvent event(PlayerConnection connection) {
        final ServerProcess process = connection.process();
        return new ServerListPingEvent(connection, description(process.config()),
                process.connection().getOnlinePlayerCount(), playerLimit(process.config()), sample(process),
                process.connection().serverIcon());
    }

    /**
     * The JSON of the answer the process gives when no listener changes it.
     *
     * @param process the process
     * @return the server list JSON
     */
    public static String json(ServerProcess process) {
        return json(description(process.config()), process.connection().getOnlinePlayerCount(),
                playerLimit(process.config()), sample(process), process.connection().serverIcon());
    }

    /**
     * The JSON of the answer an event holds once its listeners are done.
     *
     * @param event the called event
     * @return the server list JSON
     */
    public static String json(ServerListPingEvent event) {
        return json(event.getDescription(), event.getOnlinePlayers(), event.getMaxPlayers(), event.getSample(),
                event.getFavicon());
    }

    /**
     * The real limit: the seats of the connection gate, or its socket limit when seats are unlimited.
     */
    private static int playerLimit(ServerConfig config) {
        return config.maxPlayers() > 0 ? config.maxPlayers() : config.maxConnections();
    }

    private static Component description(ServerConfig config) {
        return Component.text(config.motd());
    }

    private static List<SamplePlayer> sample(ServerProcess process) {
        if (process.config().hideOnlinePlayers()) return List.of();
        final List<SamplePlayer> sample = new ArrayList<>(SAMPLE_SIZE);
        for (Player player : process.connection().getOnlinePlayers()) {
            if (sample.size() == SAMPLE_SIZE) break;
            sample.add(player.getSettings().allowServerListings()
                    ? new SamplePlayer(player.getUsername(), player.getUuid()) : ANONYMOUS);
        }
        return sample;
    }

    private static String json(Component description, int online, int max, List<SamplePlayer> sample,
                               @Nullable Favicon favicon) {
        final JsonObject version = new JsonObject();
        version.addProperty("name", MinecraftServer.VERSION_NAME);
        version.addProperty("protocol", MinecraftServer.PROTOCOL_VERSION);
        final JsonObject players = new JsonObject();
        players.addProperty("online", online);
        players.addProperty("max", max);
        if (!sample.isEmpty()) {
            final JsonArray entries = new JsonArray(sample.size());
            for (SamplePlayer player : sample) {
                final JsonObject entry = new JsonObject();
                entry.addProperty("name", player.name());
                entry.addProperty("id", player.uuid().toString());
                entries.add(entry);
            }
            players.add("sample", entries);
        }
        final JsonObject status = new JsonObject();
        status.add("version", version);
        status.add("players", players);
        status.add("description", GsonComponentSerializer.gson().serializeToTree(description));
        if (favicon != null) status.addProperty("favicon", favicon.dataUri());
        return status.toString();
    }

    private StatusResponse() {
    }
}
