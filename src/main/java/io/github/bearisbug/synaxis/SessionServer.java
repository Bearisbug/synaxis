package io.github.bearisbug.synaxis;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * Where an online mode process verifies its logins, and how ({@code docs/DESIGN-PROD.md}
 * {@code API-PROD-001}).
 *
 * @param hasJoinedUrl the {@code hasJoined} endpoint, queried with {@code username} and
 *                     {@code serverId}
 * @param sendClientIp whether the query carries the {@code ip} of the connection. Off by default:
 *                     on dual stack and mobile networks the address a client reaches Mojang from
 *                     can differ from the one it reaches the server from
 * @param timeout      how long one query may take, the login timeout still bounds the whole login
 * @param maxInFlight  queries running at the same time across all connections, the others queue
 */
public record SessionServer(URI hasJoinedUrl, boolean sendClientIp, Duration timeout, int maxInFlight) {

    /** The endpoint of Mojang's session server. */
    public static final URI MOJANG_HAS_JOINED = URI.create("https://sessionserver.mojang.com/session/minecraft/hasJoined");

    public SessionServer {
        Objects.requireNonNull(hasJoinedUrl, "hasJoinedUrl");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("The session server timeout must be positive but was " + timeout);
        if (maxInFlight < 1)
            throw new IllegalArgumentException("maxInFlight must be at least 1 but was " + maxInFlight);
    }

    /** Mojang's session server: no client address, 5 s per query, 32 queries at a time. */
    public static SessionServer mojang() {
        return at(MOJANG_HAS_JOINED);
    }

    /** The defaults of {@link #mojang()} against another {@code hasJoined} endpoint. */
    public static SessionServer at(URI hasJoinedUrl) {
        return new SessionServer(hasJoinedUrl, false, Duration.ofSeconds(5), 32);
    }

    /**
     * Whether this is Mojang's own session server rather than a stand-in: {@link #MOJANG_HAS_JOINED}
     * once normalised, the whole of it, over https on the default port. Mojang's host over plain http
     * or on another port is not, whoever is on the way answers it.
     */
    public boolean isMojang() {
        final URI url = hasJoinedUrl.normalize();
        return MOJANG_HAS_JOINED.getScheme().equalsIgnoreCase(url.getScheme())
                && MOJANG_HAS_JOINED.getHost().equalsIgnoreCase(url.getHost())
                && (url.getPort() == -1 || url.getPort() == 443)
                && url.getRawUserInfo() == null
                && MOJANG_HAS_JOINED.getRawPath().equals(url.getRawPath())
                && url.getRawQuery() == null
                && url.getRawFragment() == null;
    }
}
