package io.github.bearisbug.synaxis.app;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code host:port} of a key the host binds a port on, {@code synaxis.app.metrics.bind} and
 * {@code synaxis.app.management.bind} ({@code docs/DESIGN-PROD.md} {@code API-PROD-015},
 * {@code API-PROD-022}): the whole value is one address and one port, nothing else.
 * <p>
 * A URI parser must not read it: {@code ws://127.0.0.1@0.0.0.0:25585} has the user info
 * {@code 127.0.0.1} and the host {@code 0.0.0.0}, so a value that reads as loopback would bind
 * every interface, and a query or a fragment after the port would be dropped without a word.
 */
public final class BindAddress {

    private static final String OCTET = "(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])";
    private static final Pattern IPV4 = Pattern.compile(OCTET + "(?:\\." + OCTET + "){3}");
    private static final String LABEL = "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?";
    /** RFC 1123 labels; one made of digits and dots only is an IPv4 address or nothing. */
    private static final Pattern HOST_NAME = Pattern.compile(LABEL + "(?:\\." + LABEL + ")*");
    private static final Pattern NUMERIC = Pattern.compile("[0-9.]+");
    private static final Pattern HOST_PORT = Pattern.compile("(\\[[0-9A-Fa-f:.]+]|[A-Za-z0-9.-]+):([0-9]{1,5})");
    private static final int MAX_HOST_NAME = 253;
    private static final int MAX_PORT = 65_535;

    private BindAddress() {
    }

    /**
     * Reads a {@code host:port} value: an IPv4 address in four decimal parts, a host name, or an
     * IPv6 address in brackets without a zone, then {@code :} and a port from 0 to 65535. Blanks
     * around the whole value are dropped; nothing else may come before or after.
     *
     * @param key   the key, which every refusal names
     * @param value what the key is set to
     * @return the address; a literal is taken as written, a host name is resolved here
     * @throws IllegalArgumentException if the value is anything else: user info, a path, a query,
     *                                  a fragment, a port out of range or a malformed host
     */
    public static InetSocketAddress parse(String key, String value) {
        final Matcher matcher = HOST_PORT.matcher(value.strip());
        if (!matcher.matches()) throw malformed(key, value);
        final String host = matcher.group(1);
        final int port = Integer.parseInt(matcher.group(2));
        if (port > MAX_PORT)
            throw new IllegalArgumentException(key + "=" + value + ": the port " + port + " is not 0 to " + MAX_PORT);
        if (host.startsWith("[")) {
            try {
                return new InetSocketAddress(Inet6Address.ofLiteral(host.substring(1, host.length() - 1)), port);
            } catch (IllegalArgumentException _) {
                throw malformed(key, value);
            }
        }
        if (IPV4.matcher(host).matches()) return new InetSocketAddress(InetAddress.ofLiteral(host), port);
        if (NUMERIC.matcher(host).matches() || host.length() > MAX_HOST_NAME || !HOST_NAME.matcher(host).matches())
            throw malformed(key, value);
        return new InetSocketAddress(host, port);
    }

    /**
     * Writes an address the way {@link #parse} reads it: an IPv6 address in brackets, so that it
     * stays apart from the port and the line can be fed back into the key.
     *
     * @param address the address, resolved or not
     * @return {@code host:port}, or {@code [address]:port} for an IPv6 address
     */
    public static String format(InetSocketAddress address) {
        final String host = address.getHostString();
        return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + address.getPort();
    }

    private static IllegalArgumentException malformed(String key, String value) {
        return new IllegalArgumentException(key + "=" + value + " is not host:port: an IPv4 address, a host name "
                + "or an IPv6 address in brackets, ':' and a port from 0 to " + MAX_PORT
                + ", with no user info, path, query or fragment");
    }
}
