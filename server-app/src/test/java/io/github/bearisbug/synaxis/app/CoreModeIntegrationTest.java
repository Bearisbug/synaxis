package io.github.bearisbug.synaxis.app;

import ch.qos.logback.classic.Level;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerPluginMessageEvent;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.network.packet.server.common.PluginMessagePacket;
import io.github.bearisbug.synaxis.plugin.Admission;
import io.github.bearisbug.synaxis.plugin.AdmissionProvider;
import io.github.bearisbug.synaxis.plugin.AdmissionRequest;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The core mode of the startup contract (API-M2-010): the world, the default admission and the
 * diagnostic channel the host serves while no plugin provides gameplay.
 */
@EnvTest
class CoreModeIntegrationTest {

    private static final byte[] PAYLOAD = "ping".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path workingDirectory;

    private PluginHost host(Env env) {
        return new PluginHost(env.process(), workingDirectory.resolve("plugins"),
                new EventLedger(workingDirectory.resolve("logs"), "test", EventLedger.DEFAULT_MAX_BYTES_PER_DAY));
    }

    private static InstanceContainer core(Env env, PluginHost host) {
        return CoreMode.start(env.process(), host, Main.registerMatchDimensionType(env.process()),
                CoreMode.Setting.AUTO);
    }

    @Test
    void theCoreWorldIsRegisteredAndHasGroundToStandOn(Env env) {
        final PluginHost host = host(env);

        final InstanceContainer world = core(env, host);

        assertTrue(env.process().instance().getInstances().contains(world));
        world.loadChunk(0, 0).join();
        assertEquals(Block.STONE, world.getBlock(0, (int) CoreMode.SPAWN.y() - 1, 0));
        assertEquals(Block.AIR, world.getBlock(0, (int) CoreMode.SPAWN.y(), 0));
    }

    @Test
    void everyConnectionIsAdmittedIntoTheCoreWorld(Env env) {
        final PluginHost host = host(env);
        final InstanceContainer world = core(env, host);

        final AdmissionProvider provider = host.admissionProvider();
        assertNotNull(provider);
        assertEquals(new Admission.Accept(world, 0),
                provider.admit(new AdmissionRequest(UUID.randomUUID(), "probe", 0, 0)));

        try (LogCapture logs = LogCapture.of(AdmissionGate.class)) {
            final Player player = env.createPlayer(world, CoreMode.SPAWN);

            assertEquals(List.of(), logs.messagesAt(Level.INFO));
            assertTrue(player.getPlayerConnection().isOnline());
            assertEquals(world, player.getInstance());
        }
    }

    @Test
    void theDiagnosticChannelEchoesThePayloadBack(Env env) {
        final PluginHost host = host(env);
        final InstanceContainer world = core(env, host);
        final TestConnection connection = env.createConnection();
        final Player player = connection.connect(world, CoreMode.SPAWN);
        final Collector<PluginMessagePacket> packets = connection.trackIncoming(PluginMessagePacket.class);

        env.process().eventHandler().call(new PlayerPluginMessageEvent(player, CoreMode.ECHO_CHANNEL, PAYLOAD));

        packets.assertSingle(packet -> assertEquals(new PluginMessagePacket(CoreMode.ECHO_CHANNEL, PAYLOAD), packet));
    }
}
