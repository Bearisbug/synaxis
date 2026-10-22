package io.github.bearisbug.synaxis.app.transfer;

import org.jetbrains.annotations.Nullable;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The HMAC keys of the transfer tickets, {@code SYNAXIS_TRANSFER_HMAC_KEYS}
 * ({@code docs/DESIGN-PROD.md} {@code API-PROD-028}): {@code key_id:base64} pairs separated by
 * commas, one or two of them, every key exactly 32 bytes. The first one signs; a ticket is checked
 * with whichever its {@code key_id} names, which is what lets a key be rotated without a single
 * rejected ticket.
 */
public final class TransferKeys {

    /** The environment variable the keys come from. */
    public static final String VARIABLE = "SYNAXIS_TRANSFER_HMAC_KEYS";
    static final int KEY_BYTES = 32;

    private final int signingId;
    private final Map<Integer, byte[]> keys;

    private TransferKeys(int signingId, Map<Integer, byte[]> keys) {
        this.signingId = signingId;
        this.keys = keys;
    }

    /**
     * Parses the variable.
     *
     * @param value {@code key_id:base64[,key_id:base64]}
     * @return the keys
     * @throws IllegalArgumentException if the value breaks any of the rules above; the message never
     *                                  contains a key
     */
    public static TransferKeys parse(String value) {
        final String[] entries = value.strip().split(",", -1);
        if (entries.length > 2)
            throw new IllegalArgumentException(VARIABLE + " holds " + entries.length + " keys, at most 2 are allowed");
        final Map<Integer, byte[]> keys = new LinkedHashMap<>();
        int signing = -1;
        for (int i = 0; i < entries.length; i++) {
            final String entry = entries[i];
            final int colon = entry.indexOf(':');
            // The value is a secret and never repeated: the entry is named by its place
            if (colon <= 0)
                throw new IllegalArgumentException(VARIABLE + " entry " + (i + 1) + " of " + entries.length
                        + (entry.isBlank() ? " is empty (a trailing comma?)" : " is not key_id:base64"));
            final int id;
            try {
                id = Integer.parseInt(entry.substring(0, colon).strip());
            } catch (NumberFormatException _) {
                throw new IllegalArgumentException(VARIABLE + " has a key_id that is not a number");
            }
            if (id < 0 || id > 255) throw new IllegalArgumentException(VARIABLE + " key_id " + id + " is not in 0..255");
            final byte[] key;
            try {
                key = Base64.getDecoder().decode(entry.substring(colon + 1).strip());
            } catch (IllegalArgumentException _) {
                throw new IllegalArgumentException(VARIABLE + " key_id " + id + " is not base64");
            }
            if (key.length != KEY_BYTES)
                throw new IllegalArgumentException(VARIABLE + " key_id " + id + " is " + key.length + " bytes, not " + KEY_BYTES);
            if (keys.putIfAbsent(id, key) != null)
                throw new IllegalArgumentException(VARIABLE + " names key_id " + id + " twice");
            if (signing < 0) signing = id;
        }
        return new TransferKeys(signing, keys);
    }

    /**
     * Reads the variable from an environment.
     *
     * @param environment the environment
     * @return the keys, null if the variable is not set
     */
    public static @Nullable TransferKeys fromEnvironment(Map<String, String> environment) {
        final String value = environment.get(VARIABLE);
        return value == null || value.isBlank() ? null : parse(value);
    }

    int signingId() {
        return signingId;
    }

    @Nullable byte[] key(int id) {
        return keys.get(id);
    }

    @Override
    public String toString() {
        return "TransferKeys[signing=" + signingId + ", ids=" + keys.keySet() + "]";
    }
}
