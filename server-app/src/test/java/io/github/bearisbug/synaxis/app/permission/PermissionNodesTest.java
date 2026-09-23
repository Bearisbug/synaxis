package io.github.bearisbug.synaxis.app.permission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a granted node covers ({@code docs/DESIGN-PROD.md} {@code API-PROD-010}, §9): itself, and a
 * trailing {@code *} over whole segments.
 */
class PermissionNodesTest {

    @Test
    void aWildcardCoversWholeSegmentsBelowItsPrefix() {
        final Set<String> granted = Set.of("gm.*", "synaxis.command.kick");
        assertTrue(PermissionNodes.covers(granted, "gm.ban"));
        assertTrue(PermissionNodes.covers(granted, "gm.role.grant"));
        assertTrue(PermissionNodes.covers(granted, "synaxis.command.kick"));
        assertFalse(PermissionNodes.covers(granted, "gm"), "gm.* is under gm, not gm");
        assertFalse(PermissionNodes.covers(granted, "gmx.ban"), "a prefix of a segment is not a segment");
        assertFalse(PermissionNodes.covers(granted, "synaxis.command.stop"));
        assertFalse(PermissionNodes.covers(granted, "synaxis.command"));
        assertTrue(PermissionNodes.covers(Set.of("synaxis.*"), "synaxis.command.stop"), "any depth below");
        assertTrue(PermissionNodes.covers(Set.of("*"), "anything.at.all"));
        assertFalse(PermissionNodes.covers(Set.of(), "gm.ban"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"gm.*", "*", "GM.ban", "gm..ban", ".gm", "gm.", "gm ban", ""})
    void aQuestionOutsideTheGrammarIsRefused(String node) {
        assertThrows(IllegalArgumentException.class, () -> PermissionNodes.covers(Set.of("*"), node));
    }
}
