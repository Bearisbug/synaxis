package io.github.bearisbug.synaxis.crypto;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.interfaces.RSAKey;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public class LoginCryptTest {

    /**
     * The three digests of the minecraft.wiki encryption page ("Java Edition protocol/Encryption",
     * read 2026-09-23): SHA-1 of the bare name as a signed hexadecimal number. A public key with an
     * empty encoding and an empty secret reduce the server hash to exactly that.
     */
    @Test
    public void theServerHashIsSignedHexadecimalWithoutLeadingZeros() {
        assertEquals("4ed1f46bbe04bc756bcb17c0c7ce3e4632f06a48", LoginCrypt.serverHash("Notch", new byte[0], EMPTY_KEY));
        assertEquals("-7c9d5b0044c130109a5d7b5fb5c317c02b4e28c1", LoginCrypt.serverHash("jeb_", new byte[0], EMPTY_KEY));
        assertEquals("88e16a1019277b15d58faf0541e11910eb756f6", LoginCrypt.serverHash("simon", new byte[0], EMPTY_KEY));
    }

    @Test
    public void theServerHashCoversTheSecretAndTheKey() {
        final KeyPair keyPair = LoginCrypt.generateKeyPair();
        final byte[] secret = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
        final String hash = LoginCrypt.serverHash("", secret, keyPair.getPublic());
        assertNotEquals(hash, LoginCrypt.serverHash("", "0123456789abcdeF".getBytes(StandardCharsets.US_ASCII), keyPair.getPublic()));
        assertNotEquals(hash, LoginCrypt.serverHash("", secret, LoginCrypt.generateKeyPair().getPublic()));
    }

    @Test
    public void theKeyPairIsRsa1024() {
        assertEquals(1024, ((RSAKey) LoginCrypt.generateKeyPair().getPublic()).getModulus().bitLength());
    }

    @Test
    public void theStreamCipherCarriesOnAcrossCallsWithTheSecretAsIv() throws Exception {
        final SecretKey key = LoginCrypt.sharedSecret("0123456789abcdef".getBytes(StandardCharsets.US_ASCII));
        final byte[] plain = "two packets, one stream".getBytes(StandardCharsets.US_ASCII);
        final Cipher whole = LoginCrypt.streamCipher(Cipher.ENCRYPT_MODE, key);
        final byte[] once = whole.update(plain);
        final Cipher split = LoginCrypt.streamCipher(Cipher.ENCRYPT_MODE, key);
        final byte[] first = split.update(plain, 0, 5);
        final byte[] second = split.update(plain, 5, plain.length - 5);
        final byte[] joined = new byte[plain.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        assertArrayEquals(once, joined, "CFB8 is a byte stream, where a packet ends does not matter");
        assertArrayEquals(plain, LoginCrypt.streamCipher(Cipher.DECRYPT_MODE, key).update(once));
    }

    private static final PublicKey EMPTY_KEY = new PublicKey() {
        @Override
        public String getAlgorithm() {
            return "RSA";
        }

        @Override
        public String getFormat() {
            return "X.509";
        }

        @Override
        public byte[] getEncoded() {
            return new byte[0];
        }
    };
}
