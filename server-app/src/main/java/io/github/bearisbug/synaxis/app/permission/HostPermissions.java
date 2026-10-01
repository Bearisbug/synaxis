package io.github.bearisbug.synaxis.app.permission;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.app.store.RedisLink;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.event.player.PlayerDisconnectEvent;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.plugin.LogBudget;
import io.github.bearisbug.synaxis.plugin.Permissions;
import io.github.bearisbug.synaxis.timer.TaskSchedule;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The roles of the players online on this shard ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-011},
 * {@code API-PROD-010}), and the {@link Permissions} every plugin gets.
 * <p>
 * A player's roles are read while it logs in, after its data is loaded and before the admission.
 * They are read again for every online player every {@link #REFRESH} and at once for an account
 * named on {@code synaxis:permission}, so a change reaches every shard within that period even
 * while Redis is down. A read that started earlier never overwrites one that started later.
 * <p>
 * A player whose roles could not be read holds none until the next read: a failure to authorise
 * refuses. A process without a database ({@code synaxis.app.store=memory}) has no roles at all.
 */
public final class HostPermissions implements Permissions {

    private static final Logger LOGGER = LoggerFactory.getLogger(HostPermissions.class);

    /** How often the roles of every online player are read again (§19, {@code RB-PROD-004}). */
    public static final Duration REFRESH = Duration.ofSeconds(30);

    /** What was read for a player, and the number of the read that read it. */
    private record Entry(RoleStore.Grants grants, long read) {
    }

    private final ServerProcess process;
    private final @Nullable RoleStore roles;
    private final RedisLink redis;
    private final Duration refresh;
    private final Map<Player, Entry> loaded = new ConcurrentHashMap<>();
    private final AtomicLong reads = new AtomicLong();
    private final LogBudget failures = new LogBudget(LOGGER, "role read failures");

    /**
     * @param process the engine process
     * @param roles   where the roles live, null in a process without a database
     * @param redis   where role changes are announced, {@link RedisLink#disabled} without Redis
     * @param refresh how often every online player is read again, {@link #REFRESH} on a shard
     */
    public HostPermissions(ServerProcess process, @Nullable RoleStore roles, RedisLink redis, Duration refresh) {
        this.process = process;
        this.roles = roles;
        this.redis = redis;
        this.refresh = refresh;
    }

    /**
     * The permissions of a process without a database: nobody holds any node.
     *
     * @param process the engine process
     * @return the permissions
     */
    public static HostPermissions withoutDatabase(ServerProcess process) {
        return new HostPermissions(process, null, RedisLink.disabled("memory"), REFRESH);
    }

    /**
     * Forgets a player when it leaves, and on a shard starts the refresh and the Redis listener.
     *
     * @param hostNode the host's event node
     */
    public void attach(EventNode<Event> hostNode) {
        hostNode.addListener(PlayerDisconnectEvent.class, event -> loaded.remove(event.getPlayer()));
        if (roles == null) return;
        // The query never runs on the tick scheduler thread: a hung database would hold the tick
        process.scheduler().scheduleTask(() -> {
            Thread.ofVirtual().name("Syn-Role-Refresh").start(() -> read(List.copyOf(loaded.keySet())));
            return TaskSchedule.duration(refresh);
        }, TaskSchedule.duration(refresh));
        redis.listenPermissions(this::changed);
    }

    /**
     * Reads a player's roles, on the configuration thread of its connection.
     *
     * @param player the player
     */
    public void load(Player player) {
        if (roles == null) return;
        // Held before the query, so that a change announced while it runs reads the player again
        loaded.putIfAbsent(player, new Entry(RoleStore.Grants.NONE, 0));
        read(List.of(player));
        // Left while its roles were read: the disconnect listener has already run
        if (!player.isOnline()) loaded.remove(player);
    }

    /**
     * Reads again the roles of the players of an account online here, on the calling thread.
     *
     * @param account the account whose roles changed
     */
    void changed(UUID account) {
        final List<Player> players = loaded.keySet().stream().filter(player -> player.getUuid().equals(account)).toList();
        if (players.isEmpty()) return;
        Thread.ofVirtual().name("Syn-Role-Change").start(() -> read(players));
    }

    private void read(List<Player> players) {
        final RoleStore store = this.roles;
        if (store == null || players.isEmpty()) return;
        final long read = reads.incrementAndGet();
        final Map<UUID, RoleStore.Grants> found;
        try {
            found = store.load(players.stream().map(Player::getUuid).distinct().toList());
        } catch (SQLException e) {
            failures.warn(e, "Reading the roles of {} players failed, they keep what they had until the next read",
                    players.size());
            return;
        }
        for (Player player : players) {
            final Entry next = new Entry(found.getOrDefault(player.getUuid(), RoleStore.Grants.NONE), read);
            final Entry before = loaded.get(player);
            final Entry after = loaded.computeIfPresent(player, (_, previous) -> later(previous, next));
            // The commands a player may run are also its completions (API-PROD-011), sent once at
            // spawn; a player still logging in gets them at spawn, with what was just read
            if (before != null && after != null && !before.grants().nodes().equals(after.grants().nodes())
                    && player.getPlayerConnection().getServerState() == ConnectionState.PLAY)
                player.refreshCommands();
        }
    }

    private static Entry later(Entry previous, Entry next) {
        return previous.read() > next.read() ? previous : next;
    }

    @Override
    public boolean has(Player player, String node) {
        final Entry entry = loaded.get(player);
        return PermissionNodes.covers(entry == null ? Set.of() : entry.grants().nodes(), node);
    }

    @Override
    public Set<String> roles(Player player) {
        final Entry entry = loaded.get(player);
        return entry == null ? Set.of() : entry.grants().roles();
    }

    /**
     * Whether roles can be granted here: only a process with a database has them.
     *
     * @return true on a shard
     */
    boolean hasDatabase() {
        return roles != null;
    }

    /**
     * Grants a role, then tells every shard and reads the account again here.
     *
     * @param account the account
     * @param role    the role id
     * @return how it ended
     * @throws SQLException if the database failed
     */
    RoleStore.Change grant(UUID account, String role) throws SQLException {
        return announced(account, requireRoles().grant(account, role));
    }

    /**
     * Grants a role on a player's behalf, for no longer than the player's own grants run
     * ({@link RoleStore#handOut}), then tells every shard and reads the account again here.
     *
     * @param account   the account
     * @param role      the role id
     * @param expiresAt the earliest expiry among the player's own grants, null if none of them expires
     * @return how it ended
     * @throws SQLException if the database failed
     */
    RoleStore.Change handOut(UUID account, String role, @Nullable Instant expiresAt) throws SQLException {
        return announced(account, requireRoles().handOut(account, role, expiresAt));
    }

    /**
     * Takes a role back, then tells every shard and reads the account again here.
     *
     * @param account the account
     * @param role    the role id
     * @return how it ended
     * @throws SQLException if the database failed
     */
    RoleStore.Change revoke(UUID account, String role) throws SQLException {
        return announced(account, requireRoles().revoke(account, role));
    }

    /**
     * What a player's grants in force give it, read from the database when it asks rather than
     * taken from what this shard read last: a grant that expired or was taken back since then must
     * not let it hand out roles (§28.2).
     *
     * @param player the player running the role command
     * @return its grants in force now
     * @throws SQLException if the database failed
     */
    RoleStore.Grants grantsOf(Player player) throws SQLException {
        return requireRoles().load(List.of(player.getUuid())).getOrDefault(player.getUuid(), RoleStore.Grants.NONE);
    }

    /**
     * Whether a player may grant or take back a role: its grants in force cover {@value RoleCommand#NODE}
     * and every node the role grants. A role that does not exist is left to the grant or the
     * revocation to report.
     *
     * @param own  the player's grants, {@link #grantsOf}
     * @param role the role id
     * @return true if the role is not above the player
     * @throws SQLException if the database failed
     */
    boolean mayHandOut(RoleStore.Grants own, String role) throws SQLException {
        if (!PermissionNodes.covers(own.nodes(), RoleCommand.NODE)) return false;
        final Set<String> nodes = requireRoles().nodesOf(role);
        if (nodes == null) return true;
        for (String node : nodes) if (!PermissionNodes.includes(own.nodes(), node)) return false;
        return true;
    }

    /**
     * The roles an account holds now, online or not.
     *
     * @param account the account
     * @return the role ids
     * @throws SQLException if the database failed
     */
    Set<String> rolesOf(UUID account) throws SQLException {
        return requireRoles().load(List.of(account)).getOrDefault(account, RoleStore.Grants.NONE).roles();
    }

    private RoleStore.Change announced(UUID account, RoleStore.Change change) {
        if (change != RoleStore.Change.DONE) return change;
        redis.publishPermissionChange(account);
        // Not through Redis: this shard sees its own change whether Redis is up or not
        final List<Player> players = loaded.keySet().stream().filter(player -> player.getUuid().equals(account)).toList();
        read(players);
        return change;
    }

    private RoleStore requireRoles() {
        final RoleStore store = this.roles;
        if (store == null) throw new IllegalStateException("roles need the postgres store");
        return store;
    }
}
