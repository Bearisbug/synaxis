package net.minestom.smoke;

import net.kyori.adventure.text.Component;
import net.minestom.server.Auth;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentStringArray;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.condition.Conditions;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerPluginMessageEvent;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.LightingChunk;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.DisconnectReasons;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Minimal launcher used to smoke test the engine against a vanilla client: one flat world,
 * an echo handler on the {@code game:main} custom payload channel, and a {@code stop} console command.
 */
public class Main {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    private static final String BIND_ADDRESS = "0.0.0.0";
    private static final String PORT_PROPERTY = "smoke.port";
    private static final int DEFAULT_PORT = 25565;

    private static final String ECHO_CHANNEL = "game:main";
    private static final int MAX_ECHO_PAYLOAD = 1024;

    private static final int GROUND_HEIGHT = 40;
    private static final Pos SPAWN = new Pos(0, 42, 0);

    static void main(String[] args) {
        final int port = Integer.getInteger(PORT_PROPERTY, DEFAULT_PORT);
        final MinecraftServer server = MinecraftServer.init(new Auth.Offline());

        final InstanceContainer instance = MinecraftServer.getInstanceManager().createInstanceContainer();
        instance.setGenerator(unit -> unit.modifier().fillHeight(0, GROUND_HEIGHT, Block.STONE));
        instance.setChunkSupplier(LightingChunk::new);

        final GlobalEventHandler eventHandler = MinecraftServer.getGlobalEventHandler();
        eventHandler.addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(instance);
            event.getPlayer().setRespawnPoint(SPAWN);
        });
        eventHandler.addListener(PlayerPluginMessageEvent.class, event -> {
            if (!ECHO_CHANNEL.equals(event.getIdentifier())) return;
            final Player player = event.getPlayer();
            final byte[] response = echo(player, event.getMessage());
            if (response != null) player.sendPluginMessage(ECHO_CHANNEL, response);
        });

        MinecraftServer.getCommandManager().register(echoCommand());

        server.start(BIND_ADDRESS, port);
        LOGGER.info("game-engine smoke listening on {}:{}", BIND_ADDRESS, port);

        // Not a daemon: the shutdown sequence runs on this thread, and the JVM must not
        // exit out from under it once the tick threads are gone.
        Thread.ofPlatform().name("smoke-console").start(Main::readConsole);
    }

    /**
     * The single {@code game:main} message of M1: the payload is sent back unchanged.
     *
     * @param player  the sender
     * @param payload the received payload
     * @return the payload to send back, or null if the sender was disconnected
     */
    private static byte @Nullable [] echo(Player player, byte[] payload) {
        final var connectionId = player.getPlayerConnection().connectionId();
        if (payload.length > MAX_ECHO_PAYLOAD) {
            LOGGER.warn("Echo payload over the limit: connection_id={} bytes={} limit={}",
                    connectionId, payload.length, MAX_ECHO_PAYLOAD);
            player.kick(DisconnectReasons.PROTOCOL_ERROR);
            return null;
        }
        LOGGER.info("Echo: connection_id={} channel={} bytes={}", connectionId, ECHO_CHANNEL, payload.length);
        return payload;
    }

    private static Command echoCommand() {
        final ArgumentStringArray textArgument = ArgumentType.StringArray("text");
        final Command command = new Command("echo");
        command.setCondition(Conditions::playerOnly);
        command.setDefaultExecutor((sender, _) -> sender.sendMessage(Component.text("Usage: /echo <text>")));
        command.addSyntax((sender, context) -> {
            if (!(sender instanceof Player player)) return;
            final String text = String.join(" ", context.get(textArgument));
            final byte[] response = echo(player, text.getBytes(StandardCharsets.UTF_8));
            if (response != null)
                player.sendMessage(Component.text("echo: " + new String(response, StandardCharsets.UTF_8)));
        }, textArgument);
        return command;
    }

    private static void readConsole() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!"stop".equals(line.strip())) continue;
                LOGGER.info("Console requested a shutdown.");
                MinecraftServer.stopCleanly();
                return;
            }
        } catch (IOException e) {
            LOGGER.error("Console reader stopped", e);
        }
    }
}
