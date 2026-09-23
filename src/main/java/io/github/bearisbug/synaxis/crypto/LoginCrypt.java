package io.github.bearisbug.synaxis.crypto;

import org.jetbrains.annotations.ApiStatus;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * The cryptography of the login encryption ({@code docs/DESIGN-PROD.md} {@code API-PROD-001},
 * {@code API-PROD-002}), the same algorithms as the 26.2 {@code Crypt} class: an RSA-1024 key pair
 * per process, the shared secret RSA/PKCS#1 encrypted by the client, and AES/CFB8 with the secret
 * as its own IV on both directions of the stream.
 */
@ApiStatus.Internal
public final class LoginCrypt {

    /** The length of the AES key a vanilla client generates, in bytes. */
    public static final int SHARED_SECRET_LENGTH = 16;

    private LoginCrypt() {
    }

    /** A fresh RSA-1024 key pair, what a process encrypts its logins with. */
    public static KeyPair generateKeyPair() {
        try {
            final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(1024);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("RSA is a required algorithm of every JDK", e);
        }
    }

    /**
     * Decrypts what the client encrypted with the public key of the process.
     *
     * @throws GeneralSecurityException if the bytes are not an RSA/PKCS#1 block for this key
     */
    public static byte[] decrypt(PrivateKey key, byte[] data) throws GeneralSecurityException {
        final Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, key);
        return cipher.doFinal(data);
    }

    /**
     * The {@code serverId} the client joins and the server asks {@code hasJoined} about:
     * {@code SHA-1(serverId ‖ secret ‖ publicKey)} as a signed hexadecimal number, negative ones
     * with a minus sign and no leading zeros.
     */
    public static String serverHash(String serverId, byte[] sharedSecret, PublicKey publicKey) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-1 is a required algorithm of every JDK", e);
        }
        digest.update(serverId.getBytes(StandardCharsets.ISO_8859_1));
        digest.update(sharedSecret);
        digest.update(publicKey.getEncoded());
        return new BigInteger(digest.digest()).toString(16);
    }

    /** The shared secret as an AES key. */
    public static SecretKey sharedSecret(byte[] secret) {
        return new SecretKeySpec(secret, "AES");
    }

    /**
     * One direction of the stream cipher. It is updated for the whole life of the connection and
     * never reset per packet.
     *
     * @param mode {@link Cipher#ENCRYPT_MODE} or {@link Cipher#DECRYPT_MODE}
     */
    public static Cipher streamCipher(int mode, SecretKey key) {
        try {
            final Cipher cipher = Cipher.getInstance("AES/CFB8/NoPadding");
            cipher.init(mode, key, new IvParameterSpec(key.getEncoded()));
            return cipher;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES/CFB8 is available in every JDK", e);
        }
    }
}
