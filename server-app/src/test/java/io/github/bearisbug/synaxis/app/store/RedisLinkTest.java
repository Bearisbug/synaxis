package io.github.bearisbug.synaxis.app.store;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A {@code SYNAXIS_REDIS_URL} that is not one ({@code docs/DESIGN-PROD.md} {@code API-PROD-006}):
 * the shard refuses to start with the variable's name, never with the URL, which carries the
 * password. What {@link java.net.URI} and {@link Integer#parseInt} would say quotes the URL, and the
 * refusal line is logged with the whole cause chain, so the refusal has no cause.
 */
class RedisLinkTest {

    private static final String REFUSAL = "SYNAXIS_REDIS_URL is not redis://[user:password@]host:port[/database]; "
            + "percent-encode the reserved characters of the password";

    @Test
    void aUrlThatIsNotOneIsRefusedWithoutThePassword() {
        // A '/' as openssl rand -base64 makes, a '%' as a password generator makes, a '#'
        for (String url : List.of("redis://:Ab3+/x9cd=@valkey:6379", "redis://:p%zz@valkey:6379",
                "redis://:Ab3#x9cd@valkey:6379")) {
            final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> RedisLink.connect(url, "r01-c1").close(), url);
            assertEquals(REFUSAL, refused.getMessage(), url);
            assertNull(refused.getCause(), url);
        }
    }

    @Test
    void aPercentEncodedPasswordIsTaken() {
        assertDoesNotThrow(() -> RedisLink.connect("redis://:Ab3%2Bx9cd%3D@127.0.0.1:1/2", "r01-c1").close());
    }
}
