package io.github.bearisbug.synaxis.app.transfer;

import io.github.bearisbug.synaxis.network.packet.server.common.CookieStorePacket;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ticket layout of {@code docs/DESIGN-PROD.md} {@code API-PROD-028} pinned by the vectors of
 * §24, both directions, and the keys and cooldown the two sides share.
 */
class TransferTicketVectorsTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final Properties VECTORS = load();

    private static Properties load() {
        try (InputStream in = Objects.requireNonNull(TransferTicketVectorsTest.class.getClassLoader()
                .getResourceAsStream("vectors/transfer/tickets.properties"))) {
            final Properties properties = new Properties();
            properties.load(in);
            return properties;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String vector(String name) {
        return Objects.requireNonNull(VECTORS.getProperty(name), name);
    }

    private static TransferKeys keys() {
        return TransferKeys.parse(vector("key"));
    }

    private static TransferTicket goodFields() {
        return new TransferTicket(7, UUID.fromString(vector("good.player_uuid")), Long.parseLong(vector("good.character_id")),
                vector("good.source_shard"), vector("good.target_shard"), Long.parseLong(vector("good.state_version")),
                HEX.parseHex(vector("good.nonce")), Long.parseLong(vector("good.issued_at")),
                Long.parseLong(vector("good.expires_at")));
    }

    @Test
    void theGoodFieldsEncodeToTheGoodBytes() {
        assertEquals(vector("good"), HEX.formatHex(goodFields().encode(keys())));
    }

    @Test
    void theGoodBytesDecodeToTheGoodFields() {
        final TransferTicket decoded = TransferTicket.decode(HEX.parseHex(vector("good")), keys());
        assertEquals(goodFields(), decoded);
        assertEquals("00112233", decoded.reference(), "the first 8 hex digits of the nonce name it");
    }

    @Test
    void theBadVectorsAreRefusedAtTheCheckTheyAreFor() {
        final long now = Long.parseLong(vector("now"));
        for (String bad : new String[]{"tampered-uuid", "expired", "wrong-ver"}) {
            final TransferTicket decoded = TransferTicket.decode(HEX.parseHex(vector(bad)), keys());
            final String reason = decoded == null ? "bad-signature" : now > decoded.expiresAt() ? "expired" : "accepted";
            assertEquals(vector(bad + ".reason"), reason, bad);
        }
    }

    @Test
    void aTicketFromAnotherKeyOrCutShortIsNotOneOfOurs() {
        final byte[] good = HEX.parseHex(vector("good"));
        final byte[] other = new byte[32];
        other[0] = 1;
        assertNull(TransferTicket.decode(good, TransferKeys.parse("7:" + Base64.getEncoder().encodeToString(other))),
                "same key id, another key");
        assertNull(TransferTicket.decode(good, TransferKeys.parse("8:" + Base64.getEncoder().encodeToString(other))),
                "a key id this shard does not have");
        assertNull(TransferTicket.decode(Arrays.copyOf(good, good.length - 1), keys()));
        assertNull(TransferTicket.decode(null, keys()), "no cookie at all");
        assertNull(TransferTicket.decode(new byte[0], keys()));
    }

    @Test
    void theSecondKeyStillChecksAndTheFirstSigns() {
        final byte[] fresh = new byte[32];
        fresh[31] = 9;
        final TransferKeys rotated = TransferKeys.parse("3:" + Base64.getEncoder().encodeToString(fresh) + "," + vector("key"));

        assertNotNull(TransferTicket.decode(HEX.parseHex(vector("good")), rotated), "a ticket of the old key during the rollover");
        assertEquals(3, rotated.signingId());
        assertArrayEquals(fresh, rotated.key(rotated.signingId()));
    }

    @Test
    void theKeyVariableFollowsItsRules() {
        final String key = Base64.getEncoder().encodeToString(new byte[32]);
        assertThrows(IllegalArgumentException.class, () -> TransferKeys.parse("1:" + key + ",2:" + key + ",3:" + key));
        assertThrows(IllegalArgumentException.class, () -> TransferKeys.parse("256:" + key));
        assertThrows(IllegalArgumentException.class, () -> TransferKeys.parse("x:" + key));
        assertThrows(IllegalArgumentException.class, () -> TransferKeys.parse("1:" + key + ",1:" + key));
        assertThrows(IllegalArgumentException.class, () -> TransferKeys.parse("1:" + Base64.getEncoder().encodeToString(new byte[16])));
        assertThrows(IllegalArgumentException.class, () -> TransferKeys.parse("1:not base64!"));
        assertThrows(IllegalArgumentException.class, () -> TransferKeys.parse(key));
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> TransferKeys.parse("1:" + Base64.getEncoder().encodeToString(new byte[31])));
        assertFalse(refused.getMessage().contains(Base64.getEncoder().encodeToString(new byte[31])),
                "a refusal never repeats the key");
        assertNull(TransferKeys.fromEnvironment(Map.of()));

        // The value is a secret and never shown, so the refusal points at the entry by its place
        final IllegalArgumentException trailing = assertThrows(IllegalArgumentException.class,
                () -> TransferKeys.parse("1:" + key + ","));
        assertEquals(TransferKeys.VARIABLE + " entry 2 of 2 is empty (a trailing comma?)", trailing.getMessage());
        final IllegalArgumentException noId = assertThrows(IllegalArgumentException.class,
                () -> TransferKeys.parse("1:" + key + "," + key));
        assertEquals(TransferKeys.VARIABLE + " entry 2 of 2 is not key_id:base64", noId.getMessage());
    }

    @Test
    void aNegativeCooldownIsRefusedWithTheRangeItTakes() {
        final String before = System.getProperty("synaxis.app.transfer-cooldown-ms");
        System.setProperty("synaxis.app.transfer-cooldown-ms", "-1");
        try {
            final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    TransferCooldowns::fromSystemProperties);
            assertEquals("synaxis.app.transfer-cooldown-ms=-1 is not a whole number of milliseconds >= 0",
                    refused.getMessage());
        } finally {
            if (before == null) System.clearProperty("synaxis.app.transfer-cooldown-ms");
            else System.setProperty("synaxis.app.transfer-cooldown-ms", before);
        }
    }

    /**
     * An account cooling down no more takes no room: a shard many accounts pass through keeps only those of
     * the last cooldown, whether or not they ever ask again.
     */
    @Test
    void theCooldownsForgetTheAccountsPastTheirCooldown() {
        final AtomicLong clock = new AtomicLong(1_000_000);
        final TransferCooldowns cooldowns = new TransferCooldowns(5_000, clock::get);
        for (int i = 0; i < 5_000; i++) cooldowns.record(UUID.randomUUID(), clock.get());
        final UUID recent = UUID.randomUUID();
        clock.addAndGet(4_000);
        cooldowns.record(recent, clock.get());
        assertEquals(5_001, cooldowns.size(), "nobody is past the cooldown yet");

        clock.addAndGet(1_000);
        cooldowns.record(UUID.randomUUID(), clock.get());

        assertEquals(2, cooldowns.size(), "the 5,000 accounts past their cooldown are forgotten");
        assertTrue(cooldowns.coolingDown(recent), "an account still cooling down is kept");
    }

    @Test
    void aShardIdLongerThanTheLayoutAllowsIsRefusedAndATicketFitsACookie() {
        final TransferTicket good = goodFields();
        final TransferTicket longest = new TransferTicket(7, good.playerUuid(), 1, "s".repeat(255), "t".repeat(255), 1,
                good.nonce(), 0, 30_000);
        assertTrue(longest.encode(keys()).length <= CookieStorePacket.MAX_VALUE_LENGTH);
        final TransferTicket tooLong = new TransferTicket(7, good.playerUuid(), 1, "s".repeat(256), "t", 1,
                good.nonce(), 0, 30_000);
        assertThrows(IllegalArgumentException.class, () -> tooLong.encode(keys()));
    }

    @Test
    void theCooldownCountsFromTheLatestTransferEitherWay() {
        final AtomicLong clock = new AtomicLong(1_000_000);
        final TransferCooldowns cooldowns = new TransferCooldowns(5_000, clock::get);
        final UUID player = UUID.randomUUID();
        assertFalse(cooldowns.coolingDown(player));

        cooldowns.record(player, 1_000_000);
        clock.set(1_003_000);
        assertTrue(cooldowns.coolingDown(player), "3 s after");
        cooldowns.record(player, 999_000);
        clock.set(1_005_000);
        assertFalse(cooldowns.coolingDown(player), "5 s after the latest one, an older record does not extend it");
    }
}
