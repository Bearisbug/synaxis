package io.github.bearisbug.synaxis.plugin;

import io.github.bearisbug.synaxis.instance.Instance;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The two answers of an admission provider ({@code docs/DESIGN-M2.md} {@code API-M2-011}) refuse to be
 * built without what the host acts on, so that a provider building a broken one fails inside its own call.
 */
class AdmissionTest {

    @Test
    void anAcceptWithoutAnInstanceIsRefused() {
        final NullPointerException refused = assertThrows(NullPointerException.class,
                () -> new Admission.Accept(noInstance()));
        assertEquals("instance", refused.getMessage());
    }

    @Test
    void aRejectWithoutAReasonKeyIsRefused() {
        final NullPointerException refused = assertThrows(NullPointerException.class, () -> new Admission.Reject(noKey()));
        assertEquals("reasonKey", refused.getMessage());
        assertEquals(DisconnectKeys.INSTANCE_UNAVAILABLE, new Admission.Reject(DisconnectKeys.INSTANCE_UNAVAILABLE).reasonKey());
    }

    private static @Nullable Instance noInstance() {
        return null;
    }

    private static @Nullable String noKey() {
        return null;
    }
}
