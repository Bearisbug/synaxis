package io.github.bearisbug.synaxis.app.scene;

import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.GameMode;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.event.player.PlayerPluginMessageEvent;
import io.github.bearisbug.synaxis.event.player.PlayerSpawnEvent;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;
import io.github.bearisbug.synaxis.game.protocol.ProtocolSchema;
import io.github.bearisbug.synaxis.game.protocol.S2CHello;
import io.github.bearisbug.synaxis.game.protocol.play.EncodedComponent;
import io.github.bearisbug.synaxis.game.protocol.play.PlayMessage;
import io.github.bearisbug.synaxis.game.protocol.play.PlayProtocol;
import io.github.bearisbug.synaxis.game.protocol.play.S2CHud;
import io.github.bearisbug.synaxis.game.protocol.play.S2CScene;
import io.github.bearisbug.synaxis.game.protocol.play.Widget;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.LightingChunk;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.world.DimensionType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The fixture of the first step of {@code docs/DESIGN-PLAY.md} §26 S1 (Q0 of §6.7.6): a bare server the real
 * client of job {@code rush} joins before any scene layer exists. It binds {@code 0.0.0.0:<port>} and prints
 * {@value #READY}.
 * <p>
 * It answers the client's {@code C2S_HELLO} the way a gameplay process does, from the configuration thread
 * before the configuration ends. At the first spawn it sends on {@value PlayProtocol#CHANNEL} an
 * {@code S2C_SCENE} and then an {@code S2C_HUD} with three {@code TEXT}s: {@link #HINT}, a key of the mod's
 * language files with one argument and a colour; {@link #MISSING}, a key no language file has, with a fallback;
 * and an empty component. Every component goes through {@link ComponentBytes}, the one encoding point of the
 * host, and {@code PlayComponentVectorTest} holds them to the frozen bytes the client's decoder test reads.
 * <p>
 * The test channel {@value #CHANNEL} takes {@code move}: the player goes to a second instance of the same
 * dimension type under another dimension name, which the client sees as a Respawn into a new level, and the
 * answer {@code moved <dimension name>} follows once {@code Player#setInstance} completed.
 */
final class PlayHudFixture {
    static final String READY = "play fixture ready";
    static final String CHANNEL = "synaxis_test:play";

    /** The owner of the fixture's widgets, which the host fills with a plugin id. */
    static final String OWNER = "synaxis_test";
    static final String SCENE_ID = "synaxis_test:play";
    static final Key LOBBY = Key.key("synaxis_test", "play_lobby");
    static final Key ELSEWHERE = Key.key("synaxis_test", "play_elsewhere");

    /** The title of the scene: a key of the mod's language files without arguments. */
    static final Component TITLE = Component.translatable("rush.scene.lobby");
    /** A key of the mod's language files with one argument and a colour; the fallback differs from the text. */
    static final Component HINT = Component.translatable("rush.hint", NamedTextColor.GOLD, Component.text("1"))
            .fallback("The fallback of rush.hint, queued: %s");
    /** A key no language file has: the client shows the fallback (ADR-PLAY-007). */
    static final Component MISSING = Component.translatable("synaxis_test.missing", NamedTextColor.GREEN)
            .fallback("Shown from the fallback");

    private static final Pos SPAWN = new Pos(0.5, 41, 0.5);
    private static final int FLOOR = 40;
    private static final int RADIUS = 3;
    private static final long NOON = 6000;
    private static final long HELLO_WAIT_SECONDS = 3;

    private PlayHudFixture() {
    }

    /**
     * @param args the port
     */
    public static void main(String[] args) {
        final int port = Integer.parseInt(args[0]);
        final MinecraftServer server = MinecraftServer.init();
        final ServerProcess process = server.process();
        final InstanceContainer lobby = flat(process, LOBBY);
        final InstanceContainer elsewhere = flat(process, ELSEWHERE);

        // One future per connection, made by whichever of the two sides comes first
        final Map<UUID, CompletableFuture<byte[]>> hellos = new ConcurrentHashMap<>();
        process.eventHandler().addListener(AsyncPlayerConfigurationEvent.class, event -> {
            final Player player = event.getPlayer();
            event.setSpawningInstance(lobby);
            player.setRespawnPoint(SPAWN);
            if (!event.isFirstConfig()) return;
            try {
                hellos.computeIfAbsent(player.getUuid(), _ -> new CompletableFuture<>()).get(HELLO_WAIT_SECONDS, TimeUnit.SECONDS);
                player.sendPluginMessage("synaxis:handshake", hello());
            } catch (TimeoutException | ExecutionException _) {
                System.out.println("play fixture: no C2S_HELLO from " + player.getUsername());
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        });
        process.eventHandler().addListener(PlayerSpawnEvent.class, event -> {
            if (!event.isFirstSpawn()) return;
            final Player player = event.getPlayer();
            player.setGameMode(GameMode.CREATIVE);
            player.sendPluginMessage(PlayProtocol.CHANNEL, encode(scene()));
            player.sendPluginMessage(PlayProtocol.CHANNEL, encode(hud()));
        });
        process.eventHandler().addListener(PlayerPluginMessageEvent.class, event -> {
            final Player player = event.getPlayer();
            switch (event.getIdentifier()) {
                case "synaxis:handshake" ->
                        hellos.computeIfAbsent(player.getUuid(), _ -> new CompletableFuture<>()).complete(event.getMessage());
                case CHANNEL -> {
                    final String request = event.getMessageString().trim();
                    if (request.equals("move")) {
                        var _ = player.setInstance(elsewhere, SPAWN).thenRun(() -> reply(player, "moved " + ELSEWHERE.asString()));
                    } else {
                        reply(player, "unknown " + request);
                    }
                }
                default -> {
                }
            }
        });

        server.start("0.0.0.0", port);
        System.out.println(READY);
    }

    /** The scene the fixture enters every player into, as the host would send it. */
    static S2CScene scene() {
        return new S2CScene(1, SCENE_ID, component(TITLE), S2CScene.CAMERA_VANILLA, S2CScene.CURSOR_LOCKED, List.of());
    }

    /** The three {@code TEXT}s, in one {@code S2C_HUD}. */
    static S2CHud hud() {
        return new S2CHud(List.of(
                new S2CHud.SetWidget(OWNER, "hint", Widget.text(Widget.LEFT, 0, component(HINT), 0)),
                new S2CHud.SetWidget(OWNER, "missing", Widget.text(Widget.LEFT, 1, component(MISSING), 0)),
                new S2CHud.SetWidget(OWNER, "empty", Widget.text(Widget.LEFT, 2, EncodedComponent.NONE, 0))));
    }

    static byte[] encode(PlayMessage message) {
        final MsgBuf buf = MsgBuf.writing();
        message.write(buf);
        return buf.toByteArray();
    }

    private static EncodedComponent component(Component component) {
        return new EncodedComponent(ComponentBytes.encode(component));
    }

    private static byte[] hello() {
        final MsgBuf buf = MsgBuf.writing();
        new S2CHello(ProtocolSchema.SCHEMA_VERSION, 20, 0).write(buf);
        return buf.toByteArray();
    }

    /** A stone floor up to y 40 under a stopped noon, its chunks around the spawn lit before anybody joins. */
    private static InstanceContainer flat(ServerProcess process, Key dimensionName) {
        final InstanceContainer instance = new InstanceContainer(process, UUID.randomUUID(), DimensionType.OVERWORLD, dimensionName);
        instance.setChunkSupplier(LightingChunk::new);
        instance.setGenerator(unit -> unit.modifier().fillHeight(0, FLOOR, Block.STONE));
        final List<Chunk> chunks = new ArrayList<>();
        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int z = -RADIUS; z <= RADIUS; z++) chunks.add(instance.loadChunk(x, z).join());
        }
        LightingChunk.relight(instance, chunks);
        final var clock = Objects.requireNonNull(instance.defaultClock(), "the instance has a clock");
        clock.time(NOON);
        clock.pause();
        process.instance().registerInstance(instance);
        return instance;
    }

    private static void reply(Player player, String text) {
        player.sendPluginMessage(CHANNEL, text.getBytes(StandardCharsets.UTF_8));
    }
}
