package dev.gameengine.instance.gamerule;

import dev.gameengine.coordinate.Pos;
import dev.gameengine.event.player.PlayerGameRulesRequestEvent;
import dev.gameengine.event.player.PlayerSetGameRulesEvent;
import dev.gameengine.network.packet.client.play.ClientSetGameRulesPacket;
import dev.gameengine.network.packet.client.play.ClientStatusPacket;
import dev.gameengine.network.packet.server.play.GameRuleValuesPacket;
import dev.gameengine.testing.Env;
import dev.gameengine.testing.EnvTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

@EnvTest
public class GameRuleEventIntegrationTest {

    @Test
    public void requestGameRuleValues(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 43, 0));
        var response = connection.trackIncoming(GameRuleValuesPacket.class);
        var event = env.listen(PlayerGameRulesRequestEvent.class);

        env.process().eventHandler().addListener(PlayerGameRulesRequestEvent.class, it -> {
            it.getPlayer().sendPacket(new GameRuleValuesPacket(
                    Map.of(GameRule.COMMAND_BLOCKS_WORK.registryKey(), "false")));
        });
        event.followup();

        player.addPacketToQueue(new ClientStatusPacket(ClientStatusPacket.Action.REQUEST_GAMERULE_VALUES));
        player.interpretPacketQueue();
        response.assertSingle(it ->
                Assertions.assertEquals("false", it.values().get(GameRule.COMMAND_BLOCKS_WORK)));
    }

    @Test
    public void setGameRuleValues(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 43, 0));
        var event = env.listen(PlayerSetGameRulesEvent.class);
        var entry = new ClientSetGameRulesPacket.Entry(GameRule.COMMAND_BLOCKS_WORK.registryKey(), "false");
        event.followup(it -> Assertions.assertEquals(entry, it.getRequestedRules().getFirst()));

        player.addPacketToQueue(new ClientSetGameRulesPacket(List.of(entry)));
        player.interpretPacketQueue();
    }
}
