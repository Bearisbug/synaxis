package io.github.bearisbug.synaxis.plugin;

import org.jetbrains.annotations.Nullable;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Who a ban applies to ({@code docs/DESIGN-PROD.md} API-PROD-009): one account or one network.
 * There is no ban by name: a Mojang name can change hands.
 */
public sealed interface BanTarget {

    /**
     * Whether a connecting player falls under this target.
     *
     * @param uuid the account of the player
     * @param ip   the address it connects from, null when it has none
     * @return true if it does
     */
    boolean covers(UUID uuid, @Nullable InetAddress ip);

    /**
     * One account, which need not have logged in yet.
     *
     * @param uuid the account
     */
    record ByUuid(UUID uuid) implements BanTarget {

        /**
         * @param uuid the account
         */
        public ByUuid {
            Objects.requireNonNull(uuid, "uuid");
        }

        @Override
        public boolean covers(UUID uuid, @Nullable InetAddress ip) {
            return this.uuid.equals(uuid);
        }
    }

    /**
     * Every address of a network. It also catches every other player behind the same carrier grade
     * NAT or internet cafe exit, so it is meant for the sources of an attack.
     *
     * @param cidr the network in CIDR notation with the host bits cleared, such as
     *             {@code 203.0.113.0/24}: IPv4 from /8 to /32, IPv6 from /32 to /128
     */
    record ByNetwork(String cidr) implements BanTarget {

        /**
         * @param cidr a literal address with an optional prefix length ({@code /32} or {@code /128}
         *             without one); host bits are cleared
         * @throws IllegalArgumentException if it is not a literal address (a host name is not) or
         *                                  the prefix length is out of range
         */
        public ByNetwork {
            cidr = normalize(Objects.requireNonNull(cidr, "cidr"));
        }

        @Override
        public boolean covers(UUID uuid, @Nullable InetAddress ip) {
            if (ip == null) return false;
            final int slash = cidr.indexOf('/');
            final byte[] network = InetAddress.ofLiteral(cidr.substring(0, slash)).getAddress();
            final byte[] address = ip.getAddress();
            if (network.length != address.length) return false;
            clearHostBits(address, Integer.parseInt(cidr.substring(slash + 1)));
            return Arrays.equals(network, address);
        }

        private static String normalize(String cidr) {
            final int slash = cidr.indexOf('/');
            final String literal = slash < 0 ? cidr : cidr.substring(0, slash);
            // ofLiteral also takes the shortened IPv4 forms such as 10.0.0, which read as another address
            if (!literal.contains(":") && literal.chars().filter(c -> c == '.').count() != 3)
                throw new IllegalArgumentException("not a literal IP address: " + cidr);
            final byte[] bytes;
            try {
                bytes = InetAddress.ofLiteral(literal).getAddress();
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("not a literal IP address: " + cidr, e);
            }
            final int bits = bytes.length * 8;
            final int prefix;
            try {
                prefix = slash < 0 ? bits : Integer.parseInt(cidr.substring(slash + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("not a prefix length: " + cidr, e);
            }
            final int shortest = bytes.length == 4 ? 8 : 32;
            if (prefix < shortest || prefix > bits)
                throw new IllegalArgumentException("the prefix length of " + cidr + " is not between /"
                        + shortest + " and /" + bits);
            clearHostBits(bytes, prefix);
            try {
                return InetAddress.getByAddress(bytes).getHostAddress() + "/" + prefix;
            } catch (UnknownHostException e) {
                throw new IllegalStateException("4 or 16 bytes are always an address", e);
            }
        }

        private static void clearHostBits(byte[] address, int prefix) {
            for (int i = 0; i < address.length; i++) {
                final int kept = Math.clamp(prefix - i * 8L, 0, 8);
                address[i] &= (byte) (0xFF << (8 - kept));
            }
        }
    }
}
