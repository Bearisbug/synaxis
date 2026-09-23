package io.github.bearisbug.synaxis.app.permission;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * The grammar of a permission node and what a granted node covers ({@code docs/DESIGN-PROD.md}
 * {@code API-PROD-010}, §9): lower case and dotted, and a granted node may end in {@code *}. The
 * {@code CHECK} on {@code role_permission.node} in {@code V3__roles.sql} is the same grammar.
 */
final class PermissionNodes {

    /** A node a player can be asked about. */
    private static final Pattern NODE = Pattern.compile("[a-z0-9_-]+(\\.[a-z0-9_-]+)*");

    private PermissionNodes() {
    }

    /**
     * Whether granted nodes cover a node: the node itself, {@code *}, or {@code <prefix>.*} for any
     * prefix of whole segments ({@code gm.*} covers {@code gm.ban}, not {@code gm}).
     *
     * @param granted the nodes a player's roles grant
     * @param node    the node asked about
     * @return true if covered
     * @throws IllegalArgumentException if the node is not lower case and dotted
     */
    static boolean covers(Set<String> granted, String node) {
        if (!NODE.matcher(node).matches())
            throw new IllegalArgumentException("not a permission node: " + node + " (lower case and dotted, no *)");
        if (granted.contains(node) || granted.contains("*")) return true;
        for (int dot = node.indexOf('.'); dot >= 0; dot = node.indexOf('.', dot + 1)) {
            if (granted.contains(node.substring(0, dot + 1) + "*")) return true;
        }
        return false;
    }
}
