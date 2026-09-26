package io.github.bearisbug.synaxis.app.management;

import io.github.bearisbug.synaxis.app.BindAddress;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Whether and where the management interface is served, and the token it asks for
 * ({@code docs/DESIGN-PROD.md} {@code API-PROD-022}). Read once while the process starts.
 * <p>
 * Only the SHA-256 of the token is kept, and nothing here prints it: a process that holds no copy
 * of the token cannot log one.
 */
public final class ManagementSettings {

    public static final String ENABLED_PROPERTY = "synaxis.app.management.enabled";
    public static final String BIND_PROPERTY = "synaxis.app.management.bind";
    public static final String TOKEN_VARIABLE = "SYNAXIS_MANAGEMENT_TOKEN";
    /** Loopback only: reaching it from elsewhere is a decision the operator writes down. */
    public static final String DEFAULT_BIND = "127.0.0.1:25585";

    static final int MIN_TOKEN_LENGTH = 32;
    static final int MAX_TOKEN_LENGTH = 512;
    /** The {@code b64token} of RFC 6750, what may follow {@code Bearer} in a header. */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._~+/-]+=*");
    private static final String BEARER = "bearer ";

    private final boolean enabled;
    private final InetSocketAddress bind;
    private final byte @Nullable [] tokenDigest;

    private ManagementSettings(boolean enabled, InetSocketAddress bind, byte @Nullable [] tokenDigest) {
        this.enabled = enabled;
        this.bind = bind;
        this.tokenDigest = tokenDigest;
    }

    /**
     * Reads the settings once, while the process starts.
     *
     * @param environment where the token comes from
     * @return the settings of {@value #ENABLED_PROPERTY}, {@value #BIND_PROPERTY} and
     *         {@value #TOKEN_VARIABLE}
     * @throws IllegalArgumentException if a key is malformed, or the interface is enabled without a
     *                                  valid token
     */
    public static ManagementSettings fromSystemProperties(Map<String, String> environment) {
        return parse(System.getProperty(ENABLED_PROPERTY, "false"), System.getProperty(BIND_PROPERTY, DEFAULT_BIND),
                environment.get(TOKEN_VARIABLE));
    }

    static ManagementSettings parse(String enabledValue, String bindValue, @Nullable String token) {
        final String enabled = enabledValue.strip();
        if (!"true".equals(enabled) && !"false".equals(enabled))
            throw new IllegalArgumentException(ENABLED_PROPERTY + "=" + enabledValue + " is not true or false");
        final InetSocketAddress bind = BindAddress.parse(BIND_PROPERTY, bindValue);
        // Disabled, the token is not even looked at: nothing listens that could ask for it
        if ("false".equals(enabled)) return new ManagementSettings(false, bind, null);
        if (token == null || token.isEmpty())
            throw new IllegalArgumentException(ENABLED_PROPERTY + "=true needs " + TOKEN_VARIABLE
                    + " (see .env.example): the management interface never runs without a token");
        // The message names what is wrong with the token, never the token
        if (token.length() < MIN_TOKEN_LENGTH || token.length() > MAX_TOKEN_LENGTH || !TOKEN.matcher(token).matches())
            throw new IllegalArgumentException(TOKEN_VARIABLE + " has to be " + MIN_TOKEN_LENGTH + " to "
                    + MAX_TOKEN_LENGTH + " characters of A-Z a-z 0-9 - . _ ~ + /, optionally ending in '=' "
                    + "(openssl rand -hex 32); the one set has " + token.length() + " characters");
        return new ManagementSettings(true, bind, sha256(token));
    }

    /**
     * Returns whether the management interface is served.
     *
     * @return true when it is
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Returns where it is served.
     *
     * @return the address of {@value #BIND_PROPERTY}
     */
    public InetSocketAddress bind() {
        return bind;
    }

    /**
     * Checks the {@code Authorization} header of an upgrade request. The digests are compared, so
     * the time it takes depends neither on the token nor on how much of it was guessed right, nor on
     * the length of what was sent.
     *
     * @param authorization the header, empty or null when there is none
     * @return true only for {@code Bearer <the token>}
     */
    boolean accepts(@Nullable String authorization) {
        final byte[] expected = tokenDigest;
        if (expected == null || authorization == null) return false;
        final boolean bearer = authorization.length() > BEARER.length()
                && authorization.substring(0, BEARER.length()).toLowerCase(Locale.ROOT).equals(BEARER);
        final String presented = bearer ? authorization.substring(BEARER.length()).strip() : "";
        return MessageDigest.isEqual(sha256(presented), expected) && bearer;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // Every JDK ships SHA-256 (java.security.MessageDigest's required algorithms)
            throw new IllegalStateException(e);
        }
    }

    /** The token is written as whether it is set, never as itself. */
    @Override
    public String toString() {
        return "enabled=" + enabled + " bind=" + BindAddress.format(bind)
                + " token=" + (tokenDigest == null ? "unset" : "set");
    }
}
