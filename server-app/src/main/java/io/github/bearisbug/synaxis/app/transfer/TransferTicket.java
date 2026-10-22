package io.github.bearisbug.synaxis.app.transfer;

import io.github.bearisbug.synaxis.network.packet.server.common.CookieStorePacket;
import org.jetbrains.annotations.Nullable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;

/**
 * The transfer ticket and its only encoding ({@code docs/DESIGN-PROD.md} {@code API-PROD-028}):
 * the cookie {@code synaxis:transfer} a source shard stores on the client before it sends the
 * client to the target. Every integer is big endian:
 * {@code ver u8 = 1 | key_id u8 | player_uuid 16 | character_id u64 | source_shard (u8 length, UTF-8)
 * | target_shard (the same) | state_version u64 | nonce 16 | issued_at u64 | expires_at u64
 * | hmac 32}, the HMAC-SHA256 covering every byte before it.
 * <p>
 * The client only keeps and returns it; it can neither read what it says nor forge one. The
 * vectors in {@code server-app/src/test/resources/vectors/transfer/} pin the layout: changing it
 * means a new {@code ver} and new vectors in the same change (§24).
 *
 * @param keyId        which key signed it
 * @param playerUuid   the account it was issued for
 * @param characterId  the character whose lease was given back
 * @param sourceShard  the shard that issued it
 * @param targetShard  the only shard that accepts it
 * @param stateVersion the character version the source wrote when it let go
 * @param nonce        16 random bytes, accepted once
 * @param issuedAt     Unix milliseconds of issuing
 * @param expiresAt    Unix milliseconds after which it is refused, issuing + 30 s
 */
public record TransferTicket(int keyId, UUID playerUuid, long characterId, String sourceShard, String targetShard,
                             long stateVersion, byte[] nonce, long issuedAt, long expiresAt) {

    /** The cookie the ticket travels in. */
    public static final String COOKIE = "synaxis:transfer";
    /** The only layout there is. */
    static final int VERSION = 1;
    /** How long a ticket is accepted after it was issued. */
    public static final long LIFETIME_MS = 30_000;
    static final int NONCE_BYTES = 16;
    private static final int HMAC_BYTES = 32;
    private static final int MAX_SHARD_BYTES = 255;

    public TransferTicket {
        if (nonce.length != NONCE_BYTES) throw new IllegalArgumentException("a nonce is " + NONCE_BYTES + " bytes");
        nonce = nonce.clone();
    }

    /**
     * The first 8 hex digits of the nonce, which names a ticket in the logs and the ledger.
     *
     * @return the reference
     */
    public String reference() {
        return HexFormat.of().formatHex(nonce, 0, 4);
    }

    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    /**
     * Encodes and signs the ticket with the key its {@code keyId} names.
     *
     * @param keys the keys of this shard
     * @return the cookie value
     * @throws IllegalArgumentException if the key is unknown or a shard id is longer than 255 bytes
     */
    public byte[] encode(TransferKeys keys) {
        final byte[] key = keys.key(keyId);
        if (key == null) throw new IllegalArgumentException("key_id " + keyId + " is not a key of this shard");
        final byte[] source = shardBytes(sourceShard);
        final byte[] target = shardBytes(targetShard);
        final ByteBuffer buffer = ByteBuffer.allocate(2 + 16 + 8 + 1 + source.length + 1 + target.length + 8
                + NONCE_BYTES + 8 + 8 + HMAC_BYTES);
        buffer.put((byte) VERSION).put((byte) keyId)
                .putLong(playerUuid.getMostSignificantBits()).putLong(playerUuid.getLeastSignificantBits())
                .putLong(characterId)
                .put((byte) source.length).put(source)
                .put((byte) target.length).put(target)
                .putLong(stateVersion).put(nonce).putLong(issuedAt).putLong(expiresAt);
        buffer.put(hmac(key, buffer.array(), buffer.position()));
        return buffer.array();
    }

    /**
     * Reads and checks a cookie value: layout, version, key and signature, in constant time for the
     * signature. What it says (who, where, until when) is left to the caller.
     *
     * @param value the cookie value, null if the client had none
     * @param keys  the keys of this shard
     * @return the ticket, or null if it is not one this shard signed ({@code bad-signature})
     */
    public static @Nullable TransferTicket decode(byte @Nullable [] value, TransferKeys keys) {
        if (value == null || value.length < 2 + HMAC_BYTES || value.length > CookieStorePacket.MAX_VALUE_LENGTH) return null;
        if ((value[0] & 0xFF) != VERSION) return null;
        final int keyId = value[1] & 0xFF;
        final byte[] key = keys.key(keyId);
        if (key == null) return null;
        final int signed = value.length - HMAC_BYTES;
        if (!MessageDigest.isEqual(hmac(key, value, signed), Arrays.copyOfRange(value, signed, value.length)))
            return null;
        try {
            final ByteBuffer buffer = ByteBuffer.wrap(value, 2, signed - 2);
            final UUID player = new UUID(buffer.getLong(), buffer.getLong());
            final long characterId = buffer.getLong();
            final String source = shard(buffer);
            final String target = shard(buffer);
            final long stateVersion = buffer.getLong();
            final byte[] nonce = new byte[NONCE_BYTES];
            buffer.get(nonce);
            final long issuedAt = buffer.getLong();
            final long expiresAt = buffer.getLong();
            if (buffer.hasRemaining()) return null;
            return new TransferTicket(keyId, player, characterId, source, target, stateVersion, nonce, issuedAt, expiresAt);
        } catch (RuntimeException _) {
            // A signed value that does not parse was not signed by this layout
            return null;
        }
    }

    private static String shard(ByteBuffer buffer) {
        final byte[] bytes = new byte[buffer.get() & 0xFF];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] shardBytes(String shard) {
        final byte[] bytes = shard.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_SHARD_BYTES)
            throw new IllegalArgumentException("shard id " + shard + " is longer than " + MAX_SHARD_BYTES + " bytes");
        return bytes;
    }

    private static byte[] hmac(byte[] key, byte[] data, int length) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(data, 0, length);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is part of every JDK", e);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TransferTicket ticket && keyId == ticket.keyId && characterId == ticket.characterId
                && stateVersion == ticket.stateVersion && issuedAt == ticket.issuedAt && expiresAt == ticket.expiresAt
                && playerUuid.equals(ticket.playerUuid) && sourceShard.equals(ticket.sourceShard)
                && targetShard.equals(ticket.targetShard) && Arrays.equals(nonce, ticket.nonce);
    }

    @Override
    public int hashCode() {
        return 31 * playerUuid.hashCode() + Arrays.hashCode(nonce);
    }

    @Override
    public String toString() {
        return "TransferTicket[ref=" + reference() + ", player=" + playerUuid + ", character=" + characterId
                + ", " + sourceShard + " -> " + targetShard + ", version=" + stateVersion + "]";
    }
}
