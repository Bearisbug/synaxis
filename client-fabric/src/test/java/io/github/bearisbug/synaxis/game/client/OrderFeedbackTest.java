package io.github.bearisbug.synaxis.game.client;

import io.github.bearisbug.synaxis.game.protocol.OrderResultCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The §14 table of in band result codes, read as the client is told to show them. Drawing is stage
 * 6b; which marker and which words is decided here, where a test can read it.
 */
class OrderFeedbackTest {

    private static final Vec3 POINT = new Vec3(1.0, 64.0, 2.0);

    private static OrderFeedback feedback(OrderResultCode code) {
        return new OrderFeedback(code, POINT);
    }

    @Test
    void everyCodeOfTheProtocolCarriesTheMarkerDesignSection14GivesIt() {
        assertEquals(OrderFeedback.Marker.GREEN, feedback(OrderResultCode.OK).marker());
        assertEquals(OrderFeedback.Marker.RED, feedback(OrderResultCode.NO_PATH).marker());
        assertEquals(OrderFeedback.Marker.RED, feedback(OrderResultCode.NOT_OWNER).marker());
        assertEquals(OrderFeedback.Marker.RED, feedback(OrderResultCode.OUT_OF_BOUNDS).marker());
        assertEquals(OrderFeedback.Marker.YELLOW, feedback(OrderResultCode.BUSY).marker());
        assertEquals(OrderFeedback.Marker.YELLOW, feedback(OrderResultCode.RATE_LIMITED).marker());
        assertEquals(OrderFeedback.Marker.SILENT, feedback(OrderResultCode.STALE).marker());
    }

    /**
     * §14 spells out which of the six visible codes says something as well: {@code out-of-bounds}
     * is high frequency and stays wordless, and {@code rate-limited} is the client's own fault.
     */
    @Test
    void onlyTheThreeCodesDesignSection14GivesWordsCarryText() {
        assertNotNull(feedback(OrderResultCode.NO_PATH).text());
        assertNotNull(feedback(OrderResultCode.NOT_OWNER).text());
        assertNotNull(feedback(OrderResultCode.BUSY).text());

        assertNull(feedback(OrderResultCode.OK).text());
        assertNull(feedback(OrderResultCode.OUT_OF_BOUNDS).text());
        assertNull(feedback(OrderResultCode.RATE_LIMITED).text());
        assertNull(feedback(OrderResultCode.STALE).text());
    }

    /**
     * A translate key the mod does not ship is drawn as the key itself. The two files are the whole
     * of what the mod ships, so checking both is checking every player.
     */
    @Test
    void everyTextKeyIsInBothLanguageFiles() throws IOException {
        final String english = read("/assets/synaxis/lang/en_us.json");
        final String chinese = read("/assets/synaxis/lang/zh_cn.json");

        for (final OrderResultCode code : OrderResultCode.values()) {
            final String key = feedback(code).text();
            if (key == null) continue;
            assertTrue(english.contains('"' + key + '"'), "en_us.json is missing " + key);
            assertTrue(chinese.contains('"' + key + '"'), "zh_cn.json is missing " + key);
        }
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = OrderFeedbackTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource + " is not on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
