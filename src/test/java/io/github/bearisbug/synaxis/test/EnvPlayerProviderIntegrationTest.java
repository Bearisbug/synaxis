package io.github.bearisbug.synaxis.test;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@EnvTest
public class EnvPlayerProviderIntegrationTest {

    public static class CustomPlayer extends Player {
        public CustomPlayer(PlayerConnection playerConnection, GameProfile gameProfile) {
            super(playerConnection, gameProfile);
        }
    }

    @Test
    void testPlayerProviderUsedInEnvTest(Env env) {
        // Note: By default the test environment will use a player provider of its own to bypass the queued chunk system
        // overriding in a particular test will mean that chunk packets are not received consistently (they require the
        // chunk queue interaction). However, this is not a problem for many tests, so we do support it.

        env.process().connection().setPlayerProvider(CustomPlayer::new);
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 42, 0));
        assertInstanceOf(CustomPlayer.class, player);
    }
}
