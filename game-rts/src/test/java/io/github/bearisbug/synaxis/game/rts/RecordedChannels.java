package io.github.bearisbug.synaxis.game.rts;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.game.protocol.GameMessage;
import io.github.bearisbug.synaxis.game.protocol.GameMessages;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;
import io.github.bearisbug.synaxis.game.protocol.S2COrderResult;
import io.github.bearisbug.synaxis.plugin.PayloadHandler;
import io.github.bearisbug.synaxis.plugin.PluginChannels;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The channel capability of the host, reduced to what a test needs from it: every payload that went
 * out, decoded, with the player it went to. Registration is the plugin's business rather than the
 * channel's, and {@code RtsPluginIntegrationTest} is where it is asserted.
 */
final class RecordedChannels implements PluginChannels {

    private final List<Map.Entry<Player, GameMessage>> sent = new ArrayList<>();

    @Override
    public void register(String name, PayloadHandler handler) {
        throw new UnsupportedOperationException("the channel under test is handed its sends, not its name");
    }

    @Override
    public synchronized void send(Player player, String name, byte[] payload) {
        sent.add(Map.entry(player, GameMessages.read(MsgBuf.reading(payload))));
    }

    /**
     * The messages of one kind, whoever they went to.
     *
     * @param type the message class
     * @param <T>  that class
     * @return them, in the order they were sent
     */
    synchronized <T extends GameMessage> List<T> of(Class<T> type) {
        return sent.stream().map(Map.Entry::getValue).filter(type::isInstance).map(type::cast).toList();
    }

    /**
     * The answer of the order judged last, which is what most of the slice cases assert on.
     *
     * @return the last order result sent
     */
    S2COrderResult lastResult() {
        final List<S2COrderResult> results = of(S2COrderResult.class);
        assertFalse(results.isEmpty(), "the server answered no order at all");
        return results.getLast();
    }

    synchronized void clear() {
        sent.clear();
    }
}
