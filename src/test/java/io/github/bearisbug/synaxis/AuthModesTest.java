package io.github.bearisbug.synaxis;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every shard verifies its own players ({@code docs/DESIGN-PROD.md} {@code ADR-PROD-007}): a mode
 * that takes the identity from a proxy's forwarding lets a wrong launch argument turn into anyone
 * logging in as anyone, so there is none.
 */
class AuthModesTest {

    @Test
    void theOnlyModesAreOnlineAndOffline() {
        final Set<Class<?>> modes = Arrays.stream(Auth.class.getPermittedSubclasses()).collect(Collectors.toSet());
        assertEquals(Set.of(Auth.Online.class, Auth.Offline.class), modes);
    }
}
