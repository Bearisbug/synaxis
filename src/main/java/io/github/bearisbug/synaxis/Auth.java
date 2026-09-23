package io.github.bearisbug.synaxis;

import io.github.bearisbug.synaxis.crypto.LoginCrypt;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import org.jetbrains.annotations.Nullable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.Set;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BYTE;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.RAW_BYTES;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public sealed interface Auth {
    /**
     * Identities are not verified: the uuid is derived from the username. With {@code encrypt}
     * the connection still goes through the RSA exchange and AES/CFB8, and the encryption request
     * tells the client not to join a session ({@code shouldAuthenticate=false}).
     */
    record Offline(boolean encrypt) implements Auth {
        public Offline() {
            this(false);
        }
    }

    /**
     * Every connection is encrypted and verified once against {@code sessionServer}; uuid, name and
     * properties are the ones the session server answers with ({@code docs/DESIGN-PROD.md}
     * {@code API-PROD-001}).
     *
     * @param keyPair the RSA key pair of the process, generated once
     */
    record Online(KeyPair keyPair, SessionServer sessionServer) implements Auth {
        public Online {
            Objects.requireNonNull(keyPair, "keyPair");
            Objects.requireNonNull(sessionServer, "sessionServer");
        }

        public Online(SessionServer sessionServer) {
            this(LoginCrypt.generateKeyPair(), sessionServer);
        }

        public Online() {
            this(SessionServer.mojang());
        }
    }

    record Velocity(Key key) implements Auth {
        public static final String PLAYER_INFO_CHANNEL = "velocity:player_info";
        private static final String MAC_ALGORITHM = "HmacSHA256";
        private static final int SUPPORTED_FORWARDING_VERSION = 1;

        public Velocity(String secret) {
            this(secretKey(secret));
        }

        public boolean checkIntegrity(NetworkBuffer buffer) {
            final byte[] signature = new byte[32];
            for (int i = 0; i < signature.length; i++) {
                signature[i] = buffer.read(BYTE);
            }
            final long index = buffer.readIndex();
            final byte[] data = buffer.read(RAW_BYTES);
            buffer.readIndex(index);
            try {
                Mac mac = Mac.getInstance(MAC_ALGORITHM);
                mac.init(key);
                final byte[] mySignature = mac.doFinal(data);
                if (!MessageDigest.isEqual(signature, mySignature)) {
                    return false;
                }
            } catch (NoSuchAlgorithmException | InvalidKeyException e) {
                throw new RuntimeException(e);
            }
            final int version = buffer.read(VAR_INT);
            return version == SUPPORTED_FORWARDING_VERSION;
        }

        public static Key secretKey(String secret) {
            return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), MAC_ALGORITHM);
        }
    }

    record Bungee(@Nullable Set<String> bungeeGuardTokens) implements Auth {
        public Bungee {
            if (bungeeGuardTokens != null && bungeeGuardTokens.isEmpty()) {
                throw new IllegalArgumentException("BungeeGuard tokens cannot be empty");
            }
        }

        public Bungee() {
            this(null);
        }

        public boolean validToken(String token) {
            return bungeeGuardTokens == null || bungeeGuardTokens.contains(token);
        }

        public boolean guard() {
            return bungeeGuardTokens != null;
        }
    }
}
