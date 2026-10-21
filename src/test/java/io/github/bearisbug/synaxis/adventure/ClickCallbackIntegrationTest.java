package io.github.bearisbug.synaxis.adventure;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientCustomClickActionPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnvTest
public class ClickCallbackIntegrationTest {

    @Test
    public void unlimitedUsesWithFiniteLifetime(Env env) {
        final ClickCallbackManager manager = env.process().clickCallbackManager();
        final Player player = env.createPlayer(env.createEmptyInstance(), new Pos(0, 40, 0));

        final AtomicInteger calls = new AtomicInteger();
        final ClickEvent<ClickEvent.Payload.Custom> event = manager.createClickEvent(
                _ -> calls.incrementAndGet(),
                ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES).build());
        final ClientCustomClickActionPacket packet = clickPacket(event);

        manager.consumeCustomClick(player, packet);
        env.tick();
        manager.consumeCustomClick(player, packet);

        assertEquals(2, calls.get());
    }

    @Test
    public void limitedUsesAreExhausted(Env env) {
        final ClickCallbackManager manager = env.process().clickCallbackManager();
        final Player player = env.createPlayer(env.createEmptyInstance(), new Pos(0, 40, 0));

        final AtomicInteger calls = new AtomicInteger();
        final ClickEvent<ClickEvent.Payload.Custom> event = manager.createClickEvent(
                _ -> calls.incrementAndGet(),
                ClickCallback.Options.builder().uses(1).build());
        final ClientCustomClickActionPacket packet = clickPacket(event);

        manager.consumeCustomClick(player, packet);
        manager.consumeCustomClick(player, packet);

        assertEquals(1, calls.get());
    }

    /** A callback without limits outlives any tick: its expiry is the end of the clock. */
    @Test
    public void unlimitedUsesWithoutEndSurviveTheCleanup(Env env) {
        final ClickCallbackManager manager = env.process().clickCallbackManager();
        final Player player = env.createPlayer(env.createEmptyInstance(), new Pos(0, 40, 0));

        final AtomicInteger calls = new AtomicInteger();
        final ClickEvent<ClickEvent.Payload.Custom> event = manager.createClickEvent(
                _ -> calls.incrementAndGet(),
                ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES)
                        .lifetime(ChronoUnit.FOREVER.getDuration()).build());

        manager.tick(Long.MAX_VALUE - 1);
        manager.consumeCustomClick(player, clickPacket(event));

        assertEquals(1, calls.get());
    }

    /** A lifetime that takes the expiry past the end of the clock counts as no end, it must not wrap around. */
    @Test
    public void aLifetimeBeyondTheClockIsKept(Env env) {
        final ClickCallbackManager manager = env.process().clickCallbackManager();
        final Player player = env.createPlayer(env.createEmptyInstance(), new Pos(0, 40, 0));

        final AtomicInteger calls = new AtomicInteger();
        final ClickEvent<ClickEvent.Payload.Custom> event = manager.createClickEvent(
                _ -> calls.incrementAndGet(),
                ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES)
                        .lifetime(Duration.ofNanos(Long.MAX_VALUE)).build());
        final ClientCustomClickActionPacket packet = clickPacket(event);

        manager.consumeCustomClick(player, packet);
        manager.consumeCustomClick(player, packet);

        assertEquals(2, calls.get());
    }

    /** Options that could never fire are refused, instead of handing out a click event that does nothing. */
    @Test
    public void usesThatCanNeverFireAreRejected(Env env) {
        final ClickCallbackManager manager = env.process().clickCallbackManager();
        for (int uses : new int[]{0, -2}) {
            final ClickCallback.Options options = ClickCallback.Options.builder().uses(uses).build();
            final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> manager.createClickEvent(_ -> {}, options));
            assertEquals("uses must be > 0 or ClickCallback.UNLIMITED_USES (-1), got " + uses, error.getMessage());
        }
    }

    @Test
    public void aLifetimeThatIsNotPositiveIsRejected(Env env) {
        final ClickCallbackManager manager = env.process().clickCallbackManager();
        for (Duration lifetime : new Duration[]{Duration.ZERO, Duration.ofSeconds(-1)}) {
            final ClickCallback.Options options = ClickCallback.Options.builder().lifetime(lifetime).build();
            final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> manager.createClickEvent(_ -> {}, options));
            assertEquals("lifetime must be positive, got " + lifetime, error.getMessage());
        }
    }

    private static ClientCustomClickActionPacket clickPacket(ClickEvent<ClickEvent.Payload.Custom> event) {
        final ClickEvent.Payload.Custom payload = assertInstanceOf(ClickEvent.Payload.Custom.class, event.payload());
        final BinaryTag nbt = EngineAdventure.unwrapNbt(payload.nbt());
        return new ClientCustomClickActionPacket(payload.key(), nbt);
    }
}
