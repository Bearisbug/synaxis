package io.github.bearisbug.synaxis;

import io.github.bearisbug.synaxis.crypto.LoginCrypt;

import java.security.KeyPair;
import java.util.Objects;

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
}
