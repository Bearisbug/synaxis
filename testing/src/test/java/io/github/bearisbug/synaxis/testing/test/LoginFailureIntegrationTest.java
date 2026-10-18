package io.github.bearisbug.synaxis.testing.test;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

@EnvTest
public class LoginFailureIntegrationTest {

    /**
     * The login sequence runs on a virtual thread while the caller waits on its future, so a
     * failure in there has to be handed to the caller instead of leaving the future uncompleted.
     * The failure is the configuration ending without a spawning instance: a child node runs after
     * the node of the player, where {@code connect} sets one.
     */
    @Test
    void connectReportsLoginFailure(Env env) {
        var instance = env.createFlatInstance();
        env.process().eventHandler().addChild(EventNode.all("no-spawning-instance")
                .addListener(AsyncPlayerConfigurationEvent.class, event -> event.setSpawningInstance(null)));

        var connection = env.createConnection();
        var failure = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> assertThrows(CompletionException.class, () -> connection.connect(instance, Pos.ZERO)),
                "connect() never returned: the login failure left its future uncompleted");
        assertInstanceOf(NullPointerException.class, failure.getCause());
    }
}
