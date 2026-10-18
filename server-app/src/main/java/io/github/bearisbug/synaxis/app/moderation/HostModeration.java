package io.github.bearisbug.synaxis.app.moderation;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.app.store.RedisLink;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerPreLoginEvent;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.plugin.Ban;
import io.github.bearisbug.synaxis.plugin.BanTarget;
import io.github.bearisbug.synaxis.plugin.DisconnectKeys;
import io.github.bearisbug.synaxis.plugin.LogBudget;
import io.github.bearisbug.synaxis.plugin.Moderation;
import io.github.bearisbug.synaxis.timer.TaskSchedule;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;

/**
 * Bans and the whitelist of this shard ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-010},
 * {@code API-PROD-009}), and the {@link Moderation} every plugin gets.
 * <p>
 * The bans in force and the whitelist are held in memory and read again every {@link #REFRESH}, at
 * once when {@code synaxis:moderation} announces a change and after every change made here, so a
 * change reaches every shard within that period even while Redis is down. A read that started
 * earlier never overwrites one that started later. After each read the online players a ban covers
 * are kicked. A read that fails keeps what was read before.
 * <p>
 * A login is checked in {@link AsyncPlayerPreLoginEvent}, which the engine calls after the Mojang
 * verification, so the account is the verified one; the admission provider comes later, in the
 * configuration phase. A process without a database ({@code synaxis.app.store=memory}) bans nobody
 * and cannot change anything.
 */
public final class HostModeration implements Moderation {

    private static final Logger LOGGER = LoggerFactory.getLogger(HostModeration.class);

    /** How often the bans and the whitelist are read again (§16, {@code RB-PROD-004}). */
    public static final Duration REFRESH = Duration.ofSeconds(30);

    /** The operator of a change made from the console. */
    public static final UUID CONSOLE = new UUID(0, 0);

    /** The longest reason, {@code ban.reason} in §9. */
    static final int MAX_REASON = 256;

    /**
     * A ban expires before this instant or never: {@code ban.expires_at} in §9 is a PostgreSQL
     * {@code timestamptz}, which cannot hold much past the year 294276, and the expiry players are
     * shown has a year of four digits.
     */
    public static final Instant EXPIRY_LIMIT = Instant.parse("+10000-01-01T00:00:00Z");

    /** The second argument of {@link DisconnectKeys#BANNED} for a ban that expires. */
    static final String BANNED_UNTIL = "synaxis:banned-until";

    private static final DateTimeFormatter EXPIRY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    /** What was read, and the number of the read that read it. */
    private record Snapshot(long read, List<Ban> bans, Set<UUID> whitelist) {
        static final Snapshot EMPTY = new Snapshot(0, List.of(), Set.of());

        Snapshot plus(Ban ban) {
            final List<Ban> more = new ArrayList<>(bans);
            more.add(ban);
            return new Snapshot(read, List.copyOf(more), whitelist);
        }

        Snapshot minus(long banId) {
            return new Snapshot(read, bans.stream().filter(ban -> ban.banId() != banId).toList(), whitelist);
        }

        Snapshot listed(UUID account, boolean listed) {
            final Set<UUID> changed = new HashSet<>(whitelist);
            if (listed) changed.add(account);
            else changed.remove(account);
            return new Snapshot(read, bans, Set.copyOf(changed));
        }

        Snapshot withRead(long read) {
            return new Snapshot(read, bans, whitelist);
        }
    }

    private final ServerProcess process;
    private final @Nullable ModerationStore store;
    private final RedisLink redis;
    private final Duration refresh;
    private final boolean whitelistEnforced;
    private final BiConsumer<String, Map<String, ?>> analytics;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.EMPTY);
    private final AtomicLong reads = new AtomicLong();
    private final LogBudget failures = new LogBudget(LOGGER, "moderation read failures");

    /**
     * @param process           the engine process
     * @param store             where the bans live, null in a process without a database
     * @param redis             where changes are announced, {@link RedisLink#disabled} without Redis
     * @param refresh           how often everything is read again, {@link #REFRESH} on a shard
     * @param whitelistEnforced {@code synaxis.app.whitelist=on}
     * @param analytics         the event ledger
     */
    public HostModeration(ServerProcess process, @Nullable ModerationStore store, RedisLink redis, Duration refresh,
                          boolean whitelistEnforced, BiConsumer<String, Map<String, ?>> analytics) {
        if (store == null && whitelistEnforced)
            throw new IllegalArgumentException("synaxis.app.whitelist=on needs the postgres store "
                    + "(-Dsynaxis.app.store=postgres): the whitelist lives in the database");
        this.process = process;
        this.store = store;
        this.redis = redis;
        this.refresh = refresh;
        this.whitelistEnforced = whitelistEnforced;
        this.analytics = analytics;
    }

    /**
     * The moderation of a process without a database: nobody is banned and nothing can change.
     *
     * @param process the engine process
     * @return the moderation
     */
    public static HostModeration withoutDatabase(ServerProcess process) {
        return new HostModeration(process, null, RedisLink.disabled("memory"), REFRESH, false, (_, _) -> { });
    }

    /**
     * Reads the bans and the whitelist for the first time, before the port is bound.
     *
     * @throws SQLException if the database failed: a shard does not start without them
     */
    public void start() throws SQLException {
        if (store != null) apply(read(store));
    }

    /**
     * Checks every login, and on a shard starts the refresh and the Redis listener.
     *
     * @param hostNode the host's event node; attached before any other pre-login listener of it, so
     *                 that a banned connection goes before anything else is asked of it
     */
    public void attach(EventNode<Event> hostNode) {
        hostNode.addListener(AsyncPlayerPreLoginEvent.class, this::check);
        if (store == null) return;
        // The query never runs on the tick scheduler thread: a hung database would hold the tick
        process.scheduler().scheduleTask(() -> {
            Thread.ofVirtual().name("Syn-Moderation-Refresh").start(this::refreshAndEnforce);
            return TaskSchedule.duration(refresh);
        }, TaskSchedule.duration(refresh));
        redis.listenModeration(() -> Thread.ofVirtual().name("Syn-Moderation-Change").start(this::refreshAndEnforce));
    }

    private void check(AsyncPlayerPreLoginEvent event) {
        final PlayerConnection connection = event.getConnection();
        final UUID account = event.getGameProfile().uuid();
        final Optional<Ban> ban = activeBan(account, ipOf(connection.getRemoteAddress()));
        if (ban.isPresent()) connection.kick(bannedMessage(ban.get()));
        else if (whitelistEnforced && !snapshot.get().whitelist().contains(account))
            connection.kick(Component.translatable(DisconnectKeys.NOT_WHITELISTED));
    }

    @Override
    public Optional<Ban> activeBan(UUID uuid, @Nullable InetAddress ip) {
        final Instant now = Instant.now();
        return snapshot.get().bans().stream()
                .filter(ban -> ban.expiresAt() == null || ban.expiresAt().isAfter(now))
                .filter(ban -> ban.target().covers(uuid, ip))
                .max(Comparator.comparing(ban -> ban.expiresAt() == null ? Instant.MAX : ban.expiresAt()));
    }

    /**
     * The bans in force as this shard holds them, for the management method {@code minecraft:bans}
     * ({@code docs/DESIGN-PROD.md} {@code API-PROD-022}).
     *
     * @return the bans that have not expired, in the order they were read
     */
    public List<Ban> activeBans() {
        final Instant now = Instant.now();
        return snapshot.get().bans().stream()
                .filter(ban -> ban.expiresAt() == null || ban.expiresAt().isAfter(now))
                .toList();
    }

    @Override
    public CompletableFuture<Ban> ban(BanTarget target, String reason, @Nullable Instant expiresAt, UUID operator) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(operator, "operator");
        final int length = reason.codePointCount(0, reason.length());
        if (length == 0 || length > MAX_REASON)
            throw new IllegalArgumentException("a ban reason has 1 to " + MAX_REASON + " characters, not " + length);
        if (expiresAt != null && !expiresAt.isAfter(Instant.now()))
            throw new IllegalArgumentException("a ban that expired at " + expiresAt + " bans nobody");
        if (expiresAt != null && !expiresAt.isBefore(EXPIRY_LIMIT))
            throw new IllegalArgumentException("a ban cannot expire at " + expiresAt + ", only before " + EXPIRY_LIMIT);
        final long start = System.nanoTime();
        return changing(database -> {
            final Ban ban = database.ban(target, reason, expiresAt, operator);
            reread(database, previous -> previous.plus(ban));
            final int kicked = kickCovered();
            final Long kickedWithinMs = kicked == 0 ? null : (System.nanoTime() - start) / 1_000_000L;
            redis.publishModeration("ban", subject(target), ban.banId());
            final Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("ban_id", ban.banId());
            attributes.put("kind", target instanceof BanTarget.ByUuid ? "uuid" : "network");
            attributes.put("operator", operator);
            attributes.put("was_online", kicked > 0);
            attributes.put("kicked_within_ms", kickedWithinMs);
            analytics.accept("player_banned", attributes);
            return ban;
        });
    }

    @Override
    public CompletableFuture<Boolean> unban(long banId, UUID operator) {
        Objects.requireNonNull(operator, "operator");
        return changing(database -> {
            final Optional<String> subject = database.revoke(banId, operator);
            if (subject.isEmpty()) return false;
            reread(database, previous -> previous.minus(banId));
            redis.publishModeration("unban", subject.get(), banId);
            return true;
        });
    }

    @Override
    public CompletableFuture<Void> whitelist(UUID account, boolean listed) {
        Objects.requireNonNull(account, "account");
        return changing(database -> {
            database.whitelist(account, listed);
            reread(database, previous -> previous.listed(account, listed));
            redis.publishModeration(listed ? "whitelist-add" : "whitelist-remove", account.toString(), null);
            return null;
        });
    }

    /** A change made against the database. */
    @FunctionalInterface
    private interface Change<T> {
        T apply(ModerationStore database) throws SQLException;
    }

    private <T> CompletableFuture<T> changing(Change<T> change) {
        final CompletableFuture<T> result = new CompletableFuture<>();
        final ModerationStore database = this.store;
        if (database == null) {
            result.completeExceptionally(new IllegalStateException("bans and the whitelist need the postgres store"));
            return result;
        }
        Thread.ofVirtual().name("Syn-Moderation-Write").start(() -> {
            try {
                result.complete(change.apply(database));
            } catch (SQLException e) {
                result.completeExceptionally(new IllegalStateException("the moderation store failed: " + e.getMessage(), e));
            } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
            }
        });
        return result;
    }

    /**
     * Reads everything again after a change made here. Should that read fail, the change is put
     * into what this shard holds by hand, so that it applies here at once all the same. What is put
     * in by hand carries the number of the read that failed: a read that started before the change
     * does not see it and must not take it back, one that started after it does see it, and when
     * such a read is already in, nothing is put in by hand.
     */
    private void reread(ModerationStore database, UnaryOperator<Snapshot> byHand) {
        final long read = reads.incrementAndGet();
        try {
            final ModerationStore.Contents contents = database.load();
            apply(new Snapshot(read, contents.bans(), contents.whitelist()));
        } catch (SQLException e) {
            failures.warn(e, "Reading the bans again after a change failed, the change is applied here by hand");
            snapshot.updateAndGet(previous -> previous.read() > read ? previous : byHand.apply(previous).withRead(read));
        }
    }

    private Snapshot read(ModerationStore database) throws SQLException {
        final long read = reads.incrementAndGet();
        final ModerationStore.Contents contents = database.load();
        return new Snapshot(read, contents.bans(), contents.whitelist());
    }

    private void apply(Snapshot next) {
        snapshot.accumulateAndGet(next, (previous, candidate) -> previous.read() > candidate.read() ? previous : candidate);
    }

    private void refreshAndEnforce() {
        final ModerationStore database = this.store;
        if (database == null) return;
        try {
            apply(read(database));
        } catch (SQLException e) {
            failures.warn(e, "Reading the bans and the whitelist failed, this shard keeps what it read before");
            return;
        }
        kickCovered();
    }

    /**
     * Kicks the players on this shard, in game or still configuring, that a ban in force covers.
     *
     * @return how many were kicked
     */
    private int kickCovered() {
        final List<Player> players = new ArrayList<>(process.connection().getConfigPlayers());
        players.addAll(process.connection().getOnlinePlayers());
        int kicked = 0;
        for (Player player : players) {
            final Optional<Ban> ban = activeBan(player.getUuid(), ipOf(player.getPlayerConnection().getRemoteAddress()));
            if (ban.isEmpty()) continue;
            player.kick(bannedMessage(ban.get()));
            kicked++;
        }
        return kicked;
    }

    /**
     * What a banned player is shown: the reason and, for a ban that expires, when.
     *
     * @param ban the ban
     * @return the disconnect reason
     */
    static Component bannedMessage(Ban ban) {
        final Instant expiresAt = ban.expiresAt();
        return Component.translatable(DisconnectKeys.BANNED, Component.text(ban.reason()), expiresAt == null
                ? Component.empty()
                : Component.translatable(BANNED_UNTIL, Component.text(EXPIRY.format(expiresAt))));
    }

    static String subject(BanTarget target) {
        return switch (target) {
            case BanTarget.ByUuid(UUID uuid) -> uuid.toString();
            case BanTarget.ByNetwork(String cidr) -> cidr;
        };
    }

    /**
     * The IP address of a connection, null for one that has none.
     *
     * @param address the remote address of the connection
     * @return the IP address
     */
    static @Nullable InetAddress ipOf(SocketAddress address) {
        return address instanceof InetSocketAddress inet ? inet.getAddress() : null;
    }

    /**
     * Whether bans can be changed here: only a process with a database has them.
     *
     * @return true on a shard
     */
    boolean hasDatabase() {
        return store != null;
    }
}
