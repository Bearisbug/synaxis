package io.github.bearisbug.synaxis.crypto;

import org.jetbrains.annotations.ApiStatus;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;

@ApiStatus.Internal
public final class KeyUtils {
    public enum SignatureAlgorithm {
        SHA256withRSA,
        SHA1withRSA
    }

    private KeyUtils() {
        //no instance
    }

    public static PublicKey publicRSAKeyFrom(byte[] data) {
        final X509EncodedKeySpec spec = new X509EncodedKeySpec(data);
        final KeyFactory keyFactory;
        try {
            keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePublic(spec);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException(e);
        }
    }
}
