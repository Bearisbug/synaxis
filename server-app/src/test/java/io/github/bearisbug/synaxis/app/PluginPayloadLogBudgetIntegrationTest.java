package io.github.bearisbug.synaxis.app;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.event.player.PlayerPluginMessageEvent;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The host side of the log bound of {@code docs/DESIGN.md} §15: a client picks the channel of every
 * {@code ClientPluginMessagePacket} it sends and how many it sends, so none of the host lines an
 * inbound payload or an inbound event can reach may be written once per packet. Three of the four
 * are here; the fourth, the event ledger, is reached once per inbound order packet through
 * {@code PluginAnalytics} and is pinned by {@link EventLedgerTest}.
 */
@EnvTest
class PluginPayloadLogBudgetIntegrationTest {

    private static final byte[] PAYLOAD = "ping".getBytes(StandardCharsets.UTF_8);

    /** Big enough that a line per payload is unmistakable, small enough to stay a unit of work. */
    private static final int FLOOD = 10_000;

    private record ProbeEvent() implements Event {
    }

    @Test
    void aFloodOfPayloadsOnUnregisteredChannelsWritesOneLine(Env env) {
        final EventNode<Event> node = EventNode.all("budget-probe");
        new PluginChannelRouter().attach(node);
        final Player player = env.createPlayer(env.createFlatInstance(), new Pos(0, 41, 0));

        try (LogCapture logs = LogCapture.of(PluginChannelRouter.class)) {
            for (int i = 0; i < FLOOD; i++) {
                node.call(new PlayerPluginMessageEvent(player, "zz:game" + i, PAYLOAD));
            }

            final List<String> warnings = logs.messagesAt(Level.WARN);
            assertEquals(1, warnings.size(),
                    "a client names the channel, so a line per payload puts the log under its control");
            assertTrue(warnings.getFirst().contains("since the last report"), warnings.getFirst());
        }
    }

    @Test
    void aFloodOfPayloadsIntoAFailingHandlerWritesOneLine(Env env) {
        final EventNode<Event> node = EventNode.all("budget-probe");
        final PluginChannelRouter router = new PluginChannelRouter();
        router.attach(node);
        router.register("probe", "probe", (_, _) -> {
            throw new IllegalStateException("probe channel failure");
        });
        final Player player = env.createPlayer(env.createFlatInstance(), new Pos(0, 41, 0));

        try (LogCapture logs = LogCapture.of(PluginChannelRouter.class)) {
            for (int i = 0; i < FLOOD; i++) {
                node.call(new PlayerPluginMessageEvent(player, "probe:probe", PAYLOAD));
            }

            final List<String> errors = logs.messagesAt(Level.ERROR);
            assertEquals(1, errors.size(),
                    "a stack per payload is more expensive than a line per payload, not less");
            assertTrue(errors.getFirst().contains("plugin_id=probe"), errors.getFirst());
        }
    }

    /**
     * Why the budget is cut per registration rather than once per class: an interval a flooding
     * plugin spends is its own, and the next plugin to fail still gets its first stack written.
     */
    @Test
    void aFloodIntoOneChannelLeavesTheNextChannelItsFirstStack(Env env) {
        final EventNode<Event> node = EventNode.all("budget-probe");
        final PluginChannelRouter router = new PluginChannelRouter();
        router.attach(node);
        router.register("noisy", "probe", (_, _) -> {
            throw new IllegalStateException("noisy channel failure");
        });
        router.register("quiet", "probe", (_, _) -> {
            throw new IllegalStateException("quiet channel failure");
        });
        final Player player = env.createPlayer(env.createFlatInstance(), new Pos(0, 41, 0));

        try (LogCapture logs = LogCapture.of(PluginChannelRouter.class)) {
            for (int i = 0; i < FLOOD; i++) {
                node.call(new PlayerPluginMessageEvent(player, "noisy:probe", PAYLOAD));
            }
            node.call(new PlayerPluginMessageEvent(player, "quiet:probe", PAYLOAD));

            final List<ILoggingEvent> errors = logs.eventsAt(Level.ERROR);
            assertEquals(2, errors.size(),
                    "one budget per registration, so a flood spends only the one it owns");
            assertTrue(errors.getLast().getFormattedMessage().contains("plugin_id=quiet"),
                    errors.getLast().getFormattedMessage());
            assertNotNull(errors.getLast().getThrowableProxy(),
                    "it is the first failure of its own budget, so it still carries the stack");
        }
    }

    @Test
    void aFloodOfEventsIntoAFailingListenerWritesOneLine() {
        final EventNode<Event> node = EventNode.all("budget-probe");
        new PluginEventsImpl("probe", node).addListener(ProbeEvent.class, _ -> {
            throw new IllegalStateException("probe event failure");
        });

        try (LogCapture logs = LogCapture.of(PluginEventsImpl.class)) {
            for (int i = 0; i < FLOOD; i++) node.call(new ProbeEvent());

            final List<String> errors = logs.messagesAt(Level.ERROR);
            assertEquals(1, errors.size(),
                    "a plugin listener on a packet driven event runs as often as the client sends");
            assertTrue(errors.getFirst().contains("plugin_id=probe"), errors.getFirst());
        }
    }
}
