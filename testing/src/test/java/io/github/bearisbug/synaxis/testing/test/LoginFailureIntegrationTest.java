package io.github.bearisbug.synaxis.testing.test;

import io.github.bearisbug.synaxis.coordinate.Pos;
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
     */
    @Test
    void connectReportsLoginFailure(Env env) {
        var instance = env.createFlatInstance();
        env.process().exception().setExceptionHandler(exception -> {
            throw new IllegalStateException("configuration failed", exception);
        });
        env.process().eventHandler().addListener(AsyncPlayerConfigurationEvent.class, _ -> {
            throw new IllegalStateException("configuration listener blew up");
        });

        var connection = env.createConnection();
        var failure = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> assertThrows(CompletionException.class, () -> connection.connect(instance, Pos.ZERO)),
                "connect() never returned: the login failure left its future uncompleted");
        assertInstanceOf(IllegalStateException.class, failure.getCause());
    }
}
