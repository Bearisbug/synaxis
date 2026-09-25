package io.github.bearisbug.synaxis.app.management;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The keys and the token of the management interface ({@code docs/DESIGN-PROD.md}
 * {@code API-PROD-022}): off and on loopback unless told otherwise, never on without a token, and
 * the token never in what the settings print.
 */
class ManagementSettingsTest {

    static final String TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void byDefaultTheInterfaceIsOffAndWouldListenOnLoopbackOnly() {
        final ManagementSettings settings = ManagementSettings.fromSystemProperties(Map.of());

        assertFalse(settings.enabled());
        assertEquals(new InetSocketAddress(InetAddress.getLoopbackAddress(), 25585), settings.bind());
        assertTrue(settings.bind().getAddress().isLoopbackAddress(), settings.bind().toString());
        assertFalse(settings.accepts("Bearer " + TOKEN), "a disabled interface accepts no token");
    }

    @Test
    void enabledWithoutAValidTokenRefusesToStartWithoutPrintingIt() {
        final IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> ManagementSettings.parse("true", ManagementSettings.DEFAULT_BIND, null));
        assertTrue(missing.getMessage().contains("SYNAXIS_MANAGEMENT_TOKEN"), missing.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> ManagementSettings.parse("true", ManagementSettings.DEFAULT_BIND, ""));

        final String shortToken = "abc123abc123abc123abc123abc123a";
        final IllegalArgumentException tooShort = assertThrows(IllegalArgumentException.class,
                () -> ManagementSettings.parse("true", ManagementSettings.DEFAULT_BIND, shortToken));
        assertFalse(tooShort.getMessage().contains(shortToken), tooShort.getMessage());
        assertTrue(tooShort.getMessage().contains("31 characters"), tooShort.getMessage());

        final String spaced = TOKEN.substring(0, 40) + " " + TOKEN.substring(41);
        assertThrows(IllegalArgumentException.class,
                () -> ManagementSettings.parse("true", ManagementSettings.DEFAULT_BIND, spaced));
        assertThrows(IllegalArgumentException.class,
                () -> ManagementSettings.parse("true", ManagementSettings.DEFAULT_BIND, "x".repeat(513)));
        // 32 characters, the b64token alphabet and trailing padding are all fine
        ManagementSettings.parse("true", ManagementSettings.DEFAULT_BIND, "Ab0-._~+/Ab0-._~+/Ab0-._~+/Ab0==");
    }

    @Test
    void theKeysTakeOnlyWhatTheyDocument() {
        assertThrows(IllegalArgumentException.class, () -> ManagementSettings.parse("yes", "127.0.0.1:25585", TOKEN));
        assertThrows(IllegalArgumentException.class, () -> ManagementSettings.parse("true", "127.0.0.1", TOKEN));
        assertThrows(IllegalArgumentException.class, () -> ManagementSettings.parse("true", "127.0.0.1:1/x", TOKEN));
        assertThrows(IllegalArgumentException.class, () -> ManagementSettings.parse("true", "not a host:1", TOKEN));

        final ManagementSettings settings = ManagementSettings.parse(" true ", "10.1.2.3:0", TOKEN);
        assertTrue(settings.enabled());
        assertEquals(new InetSocketAddress("10.1.2.3", 0), settings.bind());
        // Disabled, a malformed token is not even looked at
        assertFalse(ManagementSettings.parse("false", ManagementSettings.DEFAULT_BIND, "short").enabled());
    }

    @Test
    void onlyTheBearerTokenItselfIsAccepted() {
        final ManagementSettings settings = ManagementSettings.parse("true", ManagementSettings.DEFAULT_BIND, TOKEN);

        assertTrue(settings.accepts("Bearer " + TOKEN));
        assertTrue(settings.accepts("bearer " + TOKEN), "the scheme is case insensitive");
        assertFalse(settings.accepts(null));
        assertFalse(settings.accepts(""));
        assertFalse(settings.accepts(TOKEN), "no scheme");
        assertFalse(settings.accepts("Basic " + TOKEN));
        assertFalse(settings.accepts("Bearer " + TOKEN.toUpperCase(Locale.ROOT)));
        assertFalse(settings.accepts("Bearer " + TOKEN.substring(1)));
        assertFalse(settings.accepts("Bearer " + TOKEN + "0"));
        assertFalse(settings.accepts("Bearer "));
    }

    @Test
    void theSettingsPrintWhetherATokenIsSetNeverTheToken() {
        final String printed = ManagementSettings.parse("true", "127.0.0.1:25585", TOKEN).toString();

        assertEquals("enabled=true bind=127.0.0.1:25585 token=set", printed);
        assertFalse(printed.contains(TOKEN.substring(0, 8)), printed);
        assertEquals("enabled=false bind=127.0.0.1:25585 token=unset",
                ManagementSettings.parse("false", "127.0.0.1:25585", null).toString());
    }
}
