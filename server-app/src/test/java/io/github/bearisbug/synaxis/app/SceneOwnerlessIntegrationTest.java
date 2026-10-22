package io.github.bearisbug.synaxis.app;

import ch.qos.logback.classic.Level;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerPluginMessageEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientPluginMessagePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.DisconnectPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.PluginMessagePacket;
import io.github.bearisbug.synaxis.network.player.InboundPacketLimits;
import io.github.bearisbug.synaxis.plugin.Admission;
import io.github.bearisbug.synaxis.protocol.MsgBuf;
import io.github.bearisbug.synaxis.protocol.play.ActionResultCode;
import io.github.bearisbug.synaxis.protocol.play.C2SAction;
import io.github.bearisbug.synaxis.protocol.play.C2SSceneReady;
import io.github.bearisbug.synaxis.protocol.play.EncodedComponent;
import io.github.bearisbug.synaxis.protocol.play.PlayProtocol;
import io.github.bearisbug.synaxis.protocol.play.S2CActionResult;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two kinds of process with no scene owner ({@code docs/DESIGN-PLAY.md} §6.2, {@code API-PLAY-002}, the
 * second request of the lighting session of 2026-10-12): the core mode, and a shard serving a map. Both
 * build a plugin host and so register {@code synaxis:play}, and in both the host holds the admission slot
 * itself, under its reserved id. Whatever a client sends on the channel there is dropped without a line, a
 * kick or an answer, in the play state and before it — and still counts against the inbound packet limit of
 * the engine.
 */
@EnvTest
class SceneOwnerlessIntegrationTest {

    /** The payloads of a client probing the channel, ten thousand in all. */
    private static final int FLOOD = 10_000;

    @TempDir
    Path workingDirectory;

    private @Nullable EventLedger ledger;

    /** Stops the ledger's writer before JUnit deletes the working directory, as in PluginHostIntegrationTest. */
    @AfterEach
    void closeLedger() {
        if (ledger != null) ledger.close();
    }

    private PluginHost host(Env env) {
        final EventLedger opened = new EventLedger(workingDirectory.resolve("logs"), "test",
                EventLedger.DEFAULT_MAX_BYTES_PER_DAY);
        opened.start();
        this.ledger = opened;
        return new PluginHost(env.process(), workingDirectory.resolve("plugins"), opened);
    }

    /** Every kind of payload: an action, a ready, an unknown id, a message of ours, a truncated one, nothing. */
    private static List<byte[]> probes() {
        final MsgBuf action = MsgBuf.writing();
        new C2SAction(0, 1, "probe:queue", new C2SAction.None()).write(action);
        final MsgBuf ready = MsgBuf.writing();
        new C2SSceneReady(1).write(ready);
        final MsgBuf ours = MsgBuf.writing();
        new S2CActionResult(1, ActionResultCode.OK, EncodedComponent.NONE, 0).write(ours);
        return List.of(action.toByteArray(), ready.toByteArray(), new byte[]{0x7f}, ours.toByteArray(),
                new byte[]{0x02, 0x01}, new byte[0]);
    }

    /**
     * Floods the channel the way the router hands a payload over, on this thread, from a connection in
     * {@code state}, and checks that nothing came of it.
     */
    private static void floodAndExpectNothing(Env env, Instance world, ConnectionState state) {
        final TestConnection connection = env.createConnection();
        final Player player = connection.connect(world, new Pos(0, 41, 0));
        final Collector<PluginMessagePacket> sent = connection.trackIncoming(PluginMessagePacket.class);
        final Collector<DisconnectPacket> kicks = connection.trackIncoming(DisconnectPacket.class);
        final List<byte[]> probes = probes();

        player.getPlayerConnection().setServerState(state);
        try (LogCapture logs = LogCapture.rootOfThisThread()) {
            for (int i = 0; i < FLOOD; i++)
                env.process().eventHandler().call(new PlayerPluginMessageEvent(player, PlayProtocol.CHANNEL,
                        probes.get(i % probes.size())));

            assertEquals(List.of(), logs.messagesAt(Level.INFO), "a line per payload");
            assertEquals(List.of(), logs.messagesAt(Level.WARN));
            assertEquals(List.of(), logs.messagesAt(Level.ERROR));
        } finally {
            player.getPlayerConnection().setServerState(ConnectionState.PLAY);
        }
        // A tick for the end of the player's tick, where anything queued would go out
        env.tick();
        sent.assertNoneMatch(packet -> packet.channel().equals(PlayProtocol.CHANNEL));
        kicks.assertEmpty();
        assertTrue(player.getPlayerConnection().isOnline(), "the connection was ended");
    }

    @Test
    void theCoreModeDropsEveryPlayPayloadSilently(Env env) {
        final PluginHost host = host(env);
        final Instance world = CoreMode.start(env.process(), host, Main.registerManagedDimensionType(env.process()),
                CoreMode.Setting.AUTO);

        floodAndExpectNothing(env, world, ConnectionState.PLAY);
    }

    /** A client speaking on the channel before the play state is dropped as silently, by the router. */
    @Test
    void theCoreModeDropsThePlayPayloadsOfTheConfigurationSilently(Env env) {
        final PluginHost host = host(env);
        final Instance world = CoreMode.start(env.process(), host, Main.registerManagedDimensionType(env.process()),
                CoreMode.Setting.AUTO);

        floodAndExpectNothing(env, world, ConnectionState.CONFIGURATION);
    }

    @Test
    void aShardServingAMapDropsEveryPlayPayloadSilently(Env env) {
        final PluginHost host = host(env);
        final Instance map = env.createFlatInstance();
        // What Main#serveMap does with the admission slot; a map shard loads no plugin
        host.registerHostAdmission(_ -> new Admission.Accept(map));

        floodAndExpectNothing(env, map, ConnectionState.PLAY);
    }

    @Test
    void thePayloadsOfThePlayChannelCountAgainstTheInboundPacketLimit(Env env) {
        // The read thread counts every packet before the player's queue, whatever its channel
        // (docs/DESIGN-PROD.md API-PROD-012): 750 in a window of 7 s, the next one kicks
        final InboundPacketLimits.Limiter limiter = env.process().connection().inboundLimits().newLimiter();
        final ClientPluginMessagePacket packet = new ClientPluginMessagePacket(PlayProtocol.CHANNEL, probes().getFirst());
        final long now = System.nanoTime();

        for (int i = 0; i < 750; i++)
            assertEquals(InboundPacketLimits.Verdict.ACCEPT, limiter.admit(packet, now), "packet " + i);
        assertEquals(InboundPacketLimits.Verdict.KICK, limiter.admit(packet, now));
    }
}
