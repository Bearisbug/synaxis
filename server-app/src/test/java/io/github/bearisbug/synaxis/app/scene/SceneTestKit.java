package io.github.bearisbug.synaxis.app.scene;

import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;
import io.github.bearisbug.synaxis.game.protocol.play.PlayMessage;
import io.github.bearisbug.synaxis.game.protocol.play.PlayMessages;
import io.github.bearisbug.synaxis.game.protocol.play.S2CHud;
import io.github.bearisbug.synaxis.game.protocol.play.Widget;
import io.github.bearisbug.synaxis.network.packet.server.common.PluginMessagePacket;
import io.github.bearisbug.synaxis.plugin.PluginAnalytics;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

/**
 * What the scene layer tests share: a {@link SceneService} hung on a node of its own, the ledger lines it
 * writes, and the {@code synaxis:play} messages a client got, read back.
 */
final class SceneTestKit {

    private SceneTestKit() {
    }

    /** One ledger line. */
    record Line(String event, Map<String, ?> attributes) {
    }

    /** The ledger of one test; the tick threads write to it. */
    static final class Ledger implements PluginAnalytics {

        final List<Line> lines = new CopyOnWriteArrayList<>();

        @Override
        public void record(String event, Map<String, ?> attributes) {
            lines.add(new Line(event, Map.copyOf(attributes)));
        }

        List<Line> of(String event) {
            return lines.stream().filter(line -> line.event().equals(event)).toList();
        }
    }

    /**
     * A scene layer as the plugin host builds it, attached to a node of the test's process.
     *
     * @param owner the scene owner, null for none
     */
    static SceneService service(Env env, @Nullable String owner, Ledger ledger, LongSupplier clock) {
        final SceneService service = new SceneService(env.process(), () -> owner, ledger, clock);
        final EventNode<Event> node = EventNode.all("scene-layer-test");
        env.process().eventHandler().addChild(node);
        service.attach(node);
        return service;
    }

    /**
     * Reads back what a client got on {@code synaxis:play}.
     *
     * @return the messages of the channel among what the collector caught, in order
     */
    static List<PlayMessage> messages(Collector<PluginMessagePacket> packets) {
        return packets.collect().stream().filter(packet -> packet.channel().equals(SceneService.CHANNEL))
                .map(packet -> PlayMessages.read(MsgBuf.reading(packet.data()))).toList();
    }

    /**
     * Applies HUD messages the way a client keeps its widget table, by owner and widget id.
     *
     * @return owner, then widget id, then the widget
     */
    static Map<String, Map<String, Widget>> apply(
            Map<String, Map<String, Widget>> table, List<PlayMessage> messages) {
        for (PlayMessage message : messages) {
            if (!(message instanceof S2CHud hud)) continue;
            for (S2CHud.Op op : hud.ops()) {
                switch (op) {
                    case S2CHud.SetWidget set -> table.computeIfAbsent(set.owner(), _ -> new HashMap<>())
                            .put(set.widgetId(), set.widget());
                    case S2CHud.RemoveWidget remove -> {
                        final Map<String, Widget> owned = table.get(remove.owner());
                        if (owned != null) owned.remove(remove.widgetId());
                    }
                    case S2CHud.ClearOwner clear -> table.remove(clear.owner());
                }
            }
        }
        table.values().removeIf(Map::isEmpty);
        return table;
    }
}
