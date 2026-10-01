package io.github.bearisbug.synaxis;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which {@code hasJoined} endpoint is Mojang's own ({@code docs/DESIGN-PROD.md} {@code API-PROD-001},
 * {@code REQ-PROD-036}): the effective auth mode of a process, and with it the store it may start
 * on, rests on it. Plain http to Mojang's host is answered by whoever is on the way.
 */
class SessionServerTest {

    @Test
    void mojangsEndpointOverHttpsIsMojangHoweverItIsSpelled() {
        for (String url : List.of("https://sessionserver.mojang.com/session/minecraft/hasJoined",
                "HTTPS://SessionServer.Mojang.COM/session/minecraft/hasJoined",
                "https://sessionserver.mojang.com:443/session/minecraft/hasJoined",
                "https://sessionserver.mojang.com/session/minecraft/./hasJoined"))
            assertTrue(SessionServer.at(URI.create(url)).isMojang(), url);
    }

    @Test
    void mojangsHostOverHttpOrOnAnotherPortOrPathIsNot() {
        for (String url : List.of("http://sessionserver.mojang.com/session/minecraft/hasJoined",
                "http://sessionserver.mojang.com:443/session/minecraft/hasJoined",
                "https://sessionserver.mojang.com:8443/session/minecraft/hasJoined",
                "https://sessionserver.mojang.com/session/minecraft/hasJoined?username=Steve",
                "https://proxy@sessionserver.mojang.com/session/minecraft/hasJoined",
                "https://sessionserver.mojang.com/hasJoined",
                "https://127.0.0.1/session/minecraft/hasJoined"))
            assertFalse(SessionServer.at(URI.create(url)).isMojang(), url);
    }
}
