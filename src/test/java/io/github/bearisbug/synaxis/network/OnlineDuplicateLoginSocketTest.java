package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.Auth;
import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code docs/DESIGN-PROD.md} {@code REQ-PROD-004}, same shard: in online mode the uuid is Mojang's,
 * so a second login of the account replaces the first one, and the newcomer only enters the world
 * once the old player has left it. Two real clients against a ticking process.
 */
public class OnlineDuplicateLoginSocketTest {

    @Test
    public void aSecondOnlineLoginOfTheSameAccountReplacesTheFirst() throws Exception {
        try (TestSessionServer sessions = new TestSessionServer()) {
            final UUID account = UUID.randomUUID();
            sessions.profile("Twin", account, "skin");
            // The facade, not a bare process: only MinecraftServer#start runs the tick
            final MinecraftServer server = MinecraftServer.init(
                    new Auth.Online(sessions.sessionServer(Duration.ofSeconds(10), 32)));
            final ServerProcess process = server.process();
            final Instance instance = process.instance().createInstanceContainer();
            process.eventHandler().addListener(AsyncPlayerConfigurationEvent.class,
                    event -> event.setSpawningInstance(instance));
            server.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            final int port = process.server().getPort();
            try (EncryptedLoginClient first = new EncryptedLoginClient(port, process.registries());
                 EncryptedLoginClient second = new EncryptedLoginClient(port, process.registries())) {
                first.login("Twin");
                first.configure();
                final CompletableFuture<@Nullable String> firstKicked = playInBackground(first);
                final Player old = awaitPlayer(process, account, null);

                second.login("Twin");
                second.configure();
                final CompletableFuture<@Nullable String> secondKicked = playInBackground(second);

                assertEquals("synaxis:logged-in-elsewhere", firstKicked.get(10, TimeUnit.SECONDS),
                        "the older session is told why it was dropped");
                final Player replacement = awaitPlayer(process, account, old);
                awaitUntil(() -> replacement.getInstance() == instance, "the newcomer spawns");
                assertSame(replacement, instance.getEntityTracker().getEntityByUuid(account),
                        "the entity tracker holds the newcomer, so other players see it");
                assertEquals(List.of(replacement), List.copyOf(process.connection().getOnlinePlayers()));
                assertTrue(old.isRemoved(), "the old player left the world");
                assertFalse(secondKicked.isDone(), "the newcomer stays online");
            } finally {
                process.stop();
            }
        }
    }

    /**
     * The first session is kicked while its spawn is still under way: the tick thread has taken it
     * off the join queue and waits for its spawn chunks, so it is in no instance yet but is about to
     * enter the entity tracker. The newcomer must wait for that spawn and the removal after it,
     * not slip in between and have the tracker refuse its uuid.
     */
    @Test
    public void aLoginReplacingASessionThatIsStillSpawningWaitsForItToLeave() throws Exception {
        try (TestSessionServer sessions = new TestSessionServer()) {
            final UUID account = UUID.randomUUID();
            sessions.profile("Twin", account, "skin");
            final MinecraftServer server = MinecraftServer.init(
                    new Auth.Online(sessions.sessionServer(Duration.ofSeconds(10), 32)));
            final ServerProcess process = server.process();
            final Instance instance = process.instance().createInstanceContainer();
            // Holds the first spawn in its chunk loading until the test lets it go
            final CountDownLatch chunks = new CountDownLatch(1);
            instance.setGenerator(_ -> {
                try {
                    chunks.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            });
            process.eventHandler().addListener(AsyncPlayerConfigurationEvent.class,
                    event -> event.setSpawningInstance(instance));
            server.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            final int port = process.server().getPort();
            try (EncryptedLoginClient first = new EncryptedLoginClient(port, process.registries());
                 EncryptedLoginClient second = new EncryptedLoginClient(port, process.registries())) {
                first.login("Twin");
                first.configure();
                final CompletableFuture<@Nullable String> firstKicked = playInBackground(first);
                final Player old = awaitPlayer(process, account, null);
                assertNull(old.getInstance(), "the first spawn is held in its chunk loading");

                final CompletableFuture<Void> secondConfigured = new CompletableFuture<>();
                Thread.startVirtualThread(() -> {
                    try {
                        second.login("Twin");
                        second.configure();
                        secondConfigured.complete(null);
                    } catch (Throwable t) {
                        secondConfigured.completeExceptionally(t);
                    }
                });
                assertEquals("synaxis:logged-in-elsewhere", firstKicked.get(10, TimeUnit.SECONDS));
                // Long enough for a newcomer that does not wait to get through its configuration,
                // well within the 5 second handover bound of the one that does
                try {
                    secondConfigured.get(1_500, TimeUnit.MILLISECONDS);
                } catch (TimeoutException _) {
                    // Still waiting for the first session to leave, as it should
                }
                chunks.countDown();
                secondConfigured.get(10, TimeUnit.SECONDS);
                final CompletableFuture<@Nullable String> secondKicked = playInBackground(second);

                final Player replacement = awaitPlayer(process, account, old);
                awaitUntil(() -> replacement.getInstance() == instance, "the newcomer spawns");
                assertSame(replacement, instance.getEntityTracker().getEntityByUuid(account),
                        "the entity tracker holds the newcomer, so other players see it");
                assertTrue(old.isRemoved(), "the old player left the world");
                assertFalse(secondKicked.isDone(), "the newcomer stays online");
            } finally {
                chunks.countDown();
                process.stop();
            }
        }
    }

    private static CompletableFuture<@Nullable String> playInBackground(EncryptedLoginClient client) {
        final CompletableFuture<@Nullable String> kicked = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try {
                kicked.complete(client.readPlayUntilDisconnect());
            } catch (Throwable t) {
                kicked.completeExceptionally(t);
            }
        });
        return kicked;
    }

    /** The player in the play state holding {@code uuid}, other than {@code not}. */
    private static Player awaitPlayer(ServerProcess process, UUID uuid, @Nullable Player not) throws InterruptedException {
        for (int i = 0; i < 400; i++) {
            final Player player = process.connection().getOnlinePlayerByUuid(uuid);
            if (player != null && player != not) return player;
            Thread.sleep(25);
        }
        final Player player = process.connection().getOnlinePlayerByUuid(uuid);
        assertNotNull(player, "no player of " + uuid + " reached the play state");
        throw new AssertionError("the player of " + uuid + " was never replaced");
    }

    private static void awaitUntil(BooleanSupplier condition, String what) throws InterruptedException {
        for (int i = 0; i < 400 && !condition.getAsBoolean(); i++) Thread.sleep(25);
        assertTrue(condition.getAsBoolean(), what);
    }
}
