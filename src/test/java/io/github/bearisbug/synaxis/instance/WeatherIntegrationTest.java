package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.network.packet.server.play.ChangeGameStatePacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class WeatherIntegrationTest {
    @Test
    public void weatherTest(Env env) {
        var instance = env.createFlatInstance();

        // Defaults
        Weather weather = instance.getWeather();
        assertFalse(weather.isRaining());
        assertEquals(0, weather.rainLevel());
        assertEquals(0, weather.thunderLevel());

        instance.setWeather(new Weather(1, 0.5f), 1);
        instance.tick(0);

        // Weather sent on instance join
        var connection = env.createConnection();
        var tracker = connection.trackIncoming(ChangeGameStatePacket.class);
        connection.connect(instance, new Pos(0, 0, 0));
        tracker.assertCount(4);
        List<ChangeGameStatePacket> packets = tracker.collect();
        var state = packets.get(0);
        assertEquals(ChangeGameStatePacket.Reason.BEGIN_RAINING, state.reason());

        state = packets.get(1);
        assertEquals(ChangeGameStatePacket.Reason.RAIN_LEVEL_CHANGE, state.reason());
        assertEquals(1, state.value());

        state = packets.get(2);
        assertEquals(ChangeGameStatePacket.Reason.THUNDER_LEVEL_CHANGE, state.reason());
        assertEquals(0.5f, state.value());

        // Weather change while inside instance
        var tracker2 = connection.trackIncoming(ChangeGameStatePacket.class);
        instance.setWeather(new Weather(0, 0), 2);
        instance.tick(0);
        state = tracker2.collect().getFirst();
        assertEquals(ChangeGameStatePacket.Reason.RAIN_LEVEL_CHANGE, state.reason());
        assertEquals(0.5f, state.value());
    }

    // A player joining during a transition gets the weather the other players see, not where it ends
    @Test
    public void aPlayerJoiningMidTransitionGetsTheCurrentWeather(Env env) {
        var instance = env.createFlatInstance();
        instance.setWeather(new Weather(1, 0), 100);
        for (int i = 0; i < 10; i++) instance.tick(0);

        var connection = env.createConnection();
        var tracker = connection.trackIncoming(ChangeGameStatePacket.class);
        connection.connect(instance, new Pos(0, 0, 0));
        var rainLevel = tracker.collect().stream()
                .filter(packet -> packet.reason() == ChangeGameStatePacket.Reason.RAIN_LEVEL_CHANGE)
                .toList();
        assertEquals(1, rainLevel.size());
        assertEquals(0.1f, rainLevel.getFirst().value(), 0.02f);
    }

    @Test
    public void zeroTransitionTicksIsAnArgumentError(Env env) {
        var instance = env.createFlatInstance();
        var thrown = assertThrows(IllegalArgumentException.class, () -> instance.setWeather(Weather.RAIN, 0));
        assertTrue(thrown.getMessage().contains("at least 1"), thrown.getMessage());
    }

    @Test
    public void eachOperatorChangesItsOwnLevel() {
        assertEquals(new Weather(0, 0.5f), Weather.CLEAR.withThunderLevel(_ -> 0.5f));
        assertEquals(new Weather(1, 0.5f), Weather.THUNDER.withThunderLevel(thunder -> thunder / 2));
        assertEquals(new Weather(0.5f, 1), Weather.THUNDER.withRainLevel(rain -> rain / 2));
    }
}
