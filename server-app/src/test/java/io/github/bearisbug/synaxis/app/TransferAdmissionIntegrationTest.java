package io.github.bearisbug.synaxis.app;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.app.permission.HostPermissions;
import io.github.bearisbug.synaxis.app.store.MemoryBackend;
import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.app.store.RedisLink;
import io.github.bearisbug.synaxis.app.transfer.TicketVerifier;
import io.github.bearisbug.synaxis.app.transfer.TransferCooldowns;
import io.github.bearisbug.synaxis.app.transfer.TransferKeys;
import io.github.bearisbug.synaxis.app.transfer.TransferTicket;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.DisconnectPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.plugin.Admission;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.PluginContext;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import net.kyori.adventure.text.TranslatableComponent;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A transfer through the admission gate ({@code docs/DESIGN-PROD.md} {@code API-PROD-028}): the
 * ticket read before the load, the nonce and the lease claimed in it, and a connection refused by
 * either half answered and recorded as a refused transfer, never as a failed load.
 */
@EnvTest
class TransferAdmissionIntegrationTest {

    private static final String SHARD = "r1-c1";
    private static final TransferKeys KEYS = TransferKeys.parse("7:" + Base64.getEncoder().encodeToString(new byte[32]));

    @TempDir
    Path workingDirectory;

    /** A connection that came in with the transfer intent, holding the given cookie. */
    private static final class Arriving extends PlayerConnection {
        final List<String> kicks = new CopyOnWriteArrayList<>();
        private final byte[] cookie;

        Arriving(ServerProcess process, byte[] cookie) {
            super(process);
            this.cookie = cookie;
            markTransferred(true);
            setServerState(ConnectionState.CONFIGURATION);
        }

        @Override
        public void sendPacket(SendablePacket packet) {
            if (packet instanceof DisconnectPacket disconnect && disconnect.message() instanceof TranslatableComponent key)
                kicks.add(key.key());
        }

        @Override
        public CompletableFuture<byte @Nullable []> fetchCookie(String key) {
            return CompletableFuture.completedFuture(cookie);
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("localhost", 25565);
        }
    }

    @Test
    void aTicketRefusedBeforeOrInTheLoadIsARefusedTransferAndNotAFailedLoad(Env env) throws IOException {
        final EventLedger ledger = new EventLedger(workingDirectory.resolve("logs"), "test",
                EventLedger.DEFAULT_MAX_BYTES_PER_DAY);
        ledger.start();
        final PlayerStore store = new PlayerStore(env.process(), new MemoryBackend(), RedisLink.disabled("test"), "test",
                false, PlayerStore.Options.DEFAULT, ledger::record);
        // The store half of the check meets a database that is not there
        final PGSimpleDataSource unreachable = new PGSimpleDataSource();
        unreachable.setURL("jdbc:postgresql://127.0.0.1:1/unreachable");
        final List<TransferTicket> claimed = new CopyOnWriteArrayList<>();
        final TicketVerifier verifier = new TicketVerifier(unreachable, () -> 1L, SHARD, KEYS,
                new TransferCooldowns(5_000, System::currentTimeMillis), ledger::record,
                (_, ticket) -> claimed.add(ticket));
        final PluginHost host = new PluginHost(env.process(), workingDirectory.resolve("plugins"), ledger, store, verifier,
                HostPermissions.withoutDatabase(env.process()));
        final AtomicInteger admitted = new AtomicInteger();
        host.enable(List.of(new SynaxisPlugin() {
            @Override
            public PluginDescriptor descriptor() {
                return new PluginDescriptor("probe", "1.0.0", PluginApi.API_VERSION);
            }

            @Override
            public void onEnable(PluginContext ctx) {
                final var instance = env.createFlatInstance();
                ctx.admission().register(_ -> {
                    admitted.incrementAndGet();
                    return new Admission.Accept(instance);
                });
            }

            @Override
            public void onDisable() {
            }
        }), Set.of());

        final UUID account = UUID.randomUUID();
        final long now = System.currentTimeMillis();
        final byte[] good = new TransferTicket(7, account, 1, "r2-c1", SHARD, 3, new byte[16], now,
                now + TransferTicket.LIFETIME_MS).encode(KEYS);
        final Arriving forged;
        final Arriving storeDown;
        final List<ILoggingEvent> warnings;
        try (LogCapture verifierLog = LogCapture.of(TicketVerifier.class)) {
            forged = arrive(env, UUID.randomUUID(), new byte[]{1, 2, 3});
            storeDown = arrive(env, account, good);
            warnings = verifierLog.eventsAt(Level.WARN);
        }
        host.disableAll();
        ledger.close();

        assertEquals(List.of("synaxis:transfer-rejected"), forged.kicks);
        assertEquals(List.of("synaxis:state-unavailable"), storeDown.kicks);
        assertEquals(0, admitted.get(), "neither reached the admission");
        assertEquals(List.of(), claimed, "nor its onClaimed (docs/DESIGN-M3.md API-M3-010)");
        final List<String> lines = new ArrayList<>();
        try (var files = Files.list(workingDirectory.resolve("logs"))) {
            for (Path file : files.toList()) lines.addAll(Files.readAllLines(file));
        }
        final List<String> refused = lines.stream().filter(line -> line.contains("\"transfer_rejected\"")).toList();
        assertEquals(2, refused.size(), lines.toString());
        assertTrue(refused.get(0).contains("\"reason\":\"bad-signature\""), refused.get(0));
        assertTrue(refused.get(1).contains("\"reason\":\"store-unavailable\""), refused.get(1));
        assertFalse(lines.stream().anyMatch(line -> line.contains("\"client_rejected\"")),
                "a refused ticket is not also a failed load: " + lines);
        // The failure of the store reaches an operator who reads WARN and above, with its stack
        assertEquals(1, warnings.size(), warnings.toString());
        final String reference = new TransferTicket(7, account, 1, "r2-c1", SHARD, 3, new byte[16], now,
                now + TransferTicket.LIFETIME_MS).reference();
        assertTrue(warnings.getFirst().getFormattedMessage().contains("transfer_ref=" + reference),
                warnings.getFirst().getFormattedMessage());
        assertNotNull(warnings.getFirst().getThrowableProxy(), "the cause and its stack");
    }

    private static Arriving arrive(Env env, UUID account, byte[] cookie) {
        final ServerProcess process = env.process();
        final Arriving connection = new Arriving(process, cookie);
        final Player player = process.connection().createPlayer(connection, new GameProfile(account, "Traveller"));
        assertNotNull(player);
        CompletableFuture.runAsync(() -> process.connection().doConfiguration(player, false),
                Thread.ofVirtual()::start).join();
        return connection;
    }
}
