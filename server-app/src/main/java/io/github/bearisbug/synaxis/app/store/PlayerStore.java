package io.github.bearisbug.synaxis.app.store;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.ShutdownParticipant;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.event.item.ItemDropEvent;
import io.github.bearisbug.synaxis.event.item.PickupItemEvent;
import io.github.bearisbug.synaxis.event.player.PlayerDisconnectEvent;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.DisconnectReasons;
import io.github.bearisbug.synaxis.plugin.DataSection;
import io.github.bearisbug.synaxis.plugin.DisconnectKeys;
import io.github.bearisbug.synaxis.plugin.LogBudget;
import io.github.bearisbug.synaxis.plugin.SaveResult;
import io.github.bearisbug.synaxis.plugin.ShutdownNotice;
import io.github.bearisbug.synaxis.timer.TaskSchedule;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The player data of one shard ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-005} to
 * {@code REQ-PROD-008}, {@code API-PROD-004} to {@code API-PROD-007}, §11.3).
 * <p>
 * A player's sections are loaded on the configuration thread of its connection before it is let
 * into the world, live in memory while it plays, and are written by the dirty section: every
 * {@value #DEFAULT_AUTOSAVE_SECONDS} s spread over the ticks, when a plugin asks, on logout and on
 * shutdown. Values are read and encoded on the tick thread that owns the player; writes run on
 * virtual threads, at most one at a time per character, a newer snapshot replacing a queued one.
 * <p>
 * The database says who may write a character: every write checks the lease and the version in
 * the statement that bumps the version, and a write that finds either moved on is fenced.
 */
public final class PlayerStore implements ShutdownParticipant {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerStore.class);
    private static final int DEFAULT_AUTOSAVE_SECONDS = 60;
    private static final long SAVE_ONLINE_BUDGET_SECONDS = 10;
    private static final String STOPPING = "the shard is stopping";

    /**
     * The budgets of §17 {@code store.load} and {@code store.autosave}.
     *
     * @param loadBudget          how long a login may take to load, handover included
     * @param loadPermits         loads in flight at once, the rest wait inside the budget
     * @param autosavePeriodTicks every online player is saved once per this many ticks
     * @param retryDelays         the backoff of a failed save, the last one repeating
     * @param giveUpAfter         how long a logout save is retried before the character is orphaned
     * @param handoverPoll        how often a login retries a lease another shard is letting go of
     */
    public record Options(Duration loadBudget, int loadPermits, int autosavePeriodTicks, List<Duration> retryDelays,
                          Duration giveUpAfter, Duration handoverPoll) {
        public static final Options DEFAULT = new Options(Duration.ofSeconds(5), 2, DEFAULT_AUTOSAVE_SECONDS * 20,
                List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8),
                        Duration.ofSeconds(16), Duration.ofSeconds(30)),
                Duration.ofSeconds(30), Duration.ofMillis(250));
    }

    /** §11.3 as far as this shard sees it. */
    enum LeaseState { LOADING, ONLINE, SAVING, IN_TRANSIT, FENCED, OFFLINE, ORPHANED }

    private record Hook(String pluginId, Consumer<ShutdownNotice> hook) {
    }

    /**
     * An encoded section and the commit count of its character when it was read: a snapshot read
     * before a {@code commitTogether} wrote the same section must not be written over the commit.
     */
    private record Encoded(StoreBackend.StoredSection section, long takenAt) {
    }

    /** One loaded character. */
    static final class Record {
        final Player player;
        final UUID account;
        final long characterId;
        final Map<String, Object> values = new ConcurrentHashMap<>();
        final Set<String> dirty = ConcurrentHashMap.newKeySet();
        final ReentrantLock writeLock = new ReentrantLock();
        final CompletableFuture<Void> gone = new CompletableFuture<>();
        final AtomicBoolean shutdownSnapshot = new AtomicBoolean();
        final AtomicLong version;
        // Bumped by every commitTogether once its values are in, with the sections it wrote; both
        // are written under writeLock
        final AtomicLong commits = new AtomicLong();
        final Map<String, Long> committedAt = new ConcurrentHashMap<>();
        volatile LeaseState state = LeaseState.LOADING;
        // Guarded by this record: the single slot write queue
        private @Nullable Map<String, Encoded> pending;
        private boolean release;
        // A transfer asked for and not snapshotted yet, its caller's future
        private @Nullable CompletableFuture<SaveResult> transferAsked;
        private boolean transferring;
        // The player logged out while its transfer was writing: the write goes on as the logout save
        private boolean loggedOut;
        private boolean writing;
        private final List<CompletableFuture<SaveResult>> waiting = new ArrayList<>();

        Record(Player player, UUID account, long characterId, long version) {
            this.player = player;
            this.account = account;
            this.characterId = characterId;
            this.version = new AtomicLong(version);
        }
    }

    private final ServerProcess process;
    private final StoreBackend backend;
    private final RedisLink redis;
    private final String shardId;
    private final boolean displaceHolders;
    private final Options options;
    private final BiConsumer<String, Map<String, ?>> analytics;
    private final Semaphore loads;
    private final LogBudget failures = new LogBudget(LOGGER, "store failures");
    private final Map<String, DataSection<?>> sections = new ConcurrentHashMap<>();
    private final List<Hook> shutdownHooks = new CopyOnWriteArrayList<>();
    private final Map<Player, Record> records = new ConcurrentHashMap<>();
    // The login of each account that is loading right now, completed when its load is over
    private final Map<UUID, CompletableFuture<Void>> loadingAccounts = new ConcurrentHashMap<>();
    private final Queue<Record> autosaveOrder = new ConcurrentLinkedQueue<>();
    private final Map<Record, CompletableFuture<SaveResult>> shutdownSaves = new ConcurrentHashMap<>();
    private final AtomicBoolean directSnapshotWarned = new AtomicBoolean();
    // Only touched by the autosave task: online players summed per tick, one save per period
    private long autosaveCredit;
    private volatile @Nullable ShardSession session;
    private volatile StoreMetrics metrics = StoreMetrics.NONE;
    // Guards the flip of closing against an admission: a player is either let in before the
    // shutdown save freezes everybody, and frozen with them, or refused (§11.2 closing)
    private final Object admissions = new Object();
    private volatile boolean closing;
    // Set once the shutdown save phase is over: a write still retrying is given up
    private volatile boolean saveEnded;
    private volatile long shutdownStartedNanos;
    private volatile int shutdownOnline;

    /**
     * @param process         the engine process
     * @param backend         where the characters live
     * @param redis           the disposable half, {@link RedisLink#disabled} without Redis
     * @param shardId         the id of this shard
     * @param displaceHolders whether a login takes a character over from a live session on another
     *                        shard (online mode) or is refused (offline mode, {@code REQ-PROD-004})
     * @param options         the budgets
     * @param analytics       the event ledger
     */
    public PlayerStore(ServerProcess process, StoreBackend backend, RedisLink redis, String shardId,
                       boolean displaceHolders, Options options, BiConsumer<String, Map<String, ?>> analytics) {
        this.process = process;
        this.backend = backend;
        this.redis = redis;
        this.shardId = shardId;
        this.displaceHolders = displaceHolders;
        this.options = options;
        this.analytics = analytics;
        this.loads = new Semaphore(options.loadPermits());
    }

    /**
     * The store of a process without a database ({@code synaxis.app.store=memory}): no lease, no
     * Redis, the data lives as long as the process.
     *
     * @param process   the engine process
     * @param analytics the event ledger
     * @return the store
     */
    public static PlayerStore inMemory(ServerProcess process, BiConsumer<String, Map<String, ?>> analytics) {
        return new PlayerStore(process, new MemoryBackend(), RedisLink.disabled("memory"), "memory", false,
                Options.DEFAULT, analytics);
    }

    /**
     * Reports the statements and the saves of this store to the metrics endpoint from now on.
     *
     * @param metrics where they go
     */
    public void useMetrics(StoreMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * Binds the shard session, before it is opened; a store without one (memory) has no lease.
     *
     * @param session the session of this shard
     */
    public void useSession(ShardSession session) {
        this.session = session;
    }

    /**
     * The listener of the shard session (§11.2): isolation fences every character of this shard.
     *
     * @param previous the state before
     * @param next     the state now
     */
    public void sessionChanged(ShardSession.State previous, ShardSession.State next) {
        if (next != ShardSession.State.ISOLATED) return;
        for (Record record : List.copyOf(records.values())) fence(record, "session_isolated");
    }

    /**
     * Hooks the store into the process: logout saves, the freeze of pickups and drops, the autosave
     * and the presence refresh, and the kick requests of other shards.
     *
     * @param hostNode the host's event node
     */
    public void attach(EventNode<Event> hostNode) {
        hostNode.addListener(PlayerDisconnectEvent.class, event -> loggedOut(event.getPlayer()));
        // At 3, after the plugin priorities LOWEST..HIGHEST (-2..2) and before MONITOR (4), see
        // PluginEventsImpl#tier: a frozen player's pickup or drop is cancelled whatever a plugin made
        // of it, and MONITOR sees it cancelled (API-PROD-005)
        final EventNode<Event> freeze = EventNode.all("synaxis-store-freeze").setPriority(3);
        freeze.addListener(PickupItemEvent.class, event -> {
            if (event.getLivingEntity() instanceof Player player && isFrozen(player)) event.setCancelled(true);
        });
        freeze.addListener(ItemDropEvent.class, event -> {
            if (isFrozen(event.getPlayer())) event.setCancelled(true);
        });
        hostNode.addChild(freeze);
        process.scheduler().scheduleTask(() -> {
            autosaveTick();
            return TaskSchedule.nextTick();
        }, TaskSchedule.nextTick());
        // A Redis round trip never runs on the tick scheduler thread: an unreachable Redis would
        // hold the tick for its connect timeout
        process.scheduler().scheduleTask(() -> {
            Thread.ofVirtual().name("Syn-Presence").start(this::refreshPresence);
            return TaskSchedule.seconds(10);
        }, TaskSchedule.seconds(10));
        redis.listen(this::kickRequested);
    }

    // ---- sections and hooks ----

    /**
     * Registers a section for every player; a plugin does it in {@code onEnable}, before the port
     * opens, so every player loads it.
     *
     * @param section the section
     * @throws IllegalStateException if the id is already registered
     */
    void register(DataSection<?> section) {
        if (sections.putIfAbsent(section.id(), section) != null)
            throw new IllegalStateException("data section " + section.id() + " is already registered");
    }

    boolean isRegistered(DataSection<?> section) {
        return sections.get(section.id()) == section;
    }

    void addShutdownHook(String pluginId, Consumer<ShutdownNotice> hook) {
        shutdownHooks.add(new Hook(pluginId, hook));
    }

    /** Takes back what a plugin that failed to enable registered: its sections and its hooks. */
    void discard(String pluginId, Set<String> sectionIds) {
        for (String id : sectionIds) sections.remove(id);
        shutdownHooks.removeIf(hook -> hook.pluginId().equals(pluginId));
    }

    // ---- login ----

    /**
     * Loads a player's character, on the configuration thread of its connection: takes the lease,
     * waiting up to the load budget for a holder to let go, then decodes every section.
     * A player that cannot be loaded is kicked with {@code synaxis:state-unavailable}.
     * <p>
     * The logins of one account load one at a time: both would take the lease under this session,
     * and the one that is refused or abandoned would give it back from under the other.
     *
     * @param player the player
     * @return true once loaded; false if the player was kicked
     */
    public boolean load(Player player) {
        return load(player, null);
    }

    /**
     * {@link #load(Player)} for a login that has to claim its character first: the store half of a
     * transfer ticket ({@code API-PROD-028} ⑤ ⑥). The claim runs in this account's turn, once a
     * previous login of the account on this shard has let go and right before the lease is
     * acquired, so that no other login of the account on this shard loads or plays meanwhile: the
     * lease it takes is not given back from under it, nor does it give back theirs (§11.3 end).
     *
     * @param player the player
     * @param claim  false when it refused the player, which it has kicked; null for none
     * @return true once loaded; false if the player was kicked
     */
    public boolean load(Player player, @Nullable BooleanSupplier claim) {
        final UUID account = player.getUuid();
        final long deadline = System.nanoTime() + options.loadBudget().toNanos();
        final CompletableFuture<Void> mine = new CompletableFuture<>();
        while (true) {
            final CompletableFuture<Void> other = loadingAccounts.putIfAbsent(account, mine);
            if (other == null) break;
            try {
                other.get(remaining(deadline), TimeUnit.NANOSECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return refuse(player, "interrupted");
            } catch (ExecutionException | TimeoutException _) {
                return refuse(player, "another login of this account is still loading");
            }
        }
        try {
            if (loadOrRefuse(player, deadline, claim)) return true;
            releaseUnused(account);
            return false;
        } finally {
            loadingAccounts.remove(account, mine);
            mine.complete(null);
        }
    }

    /**
     * {@code loading → offline}: a refused login gives back a lease this session may hold for it,
     * taken by the verification of a transfer ticket or by a load that failed after its acquire.
     * Not while another login of the account is loaded here: the lease is that one's. No other
     * login of the account is loading, {@link #load} lets one in at a time.
     */
    private void releaseUnused(UUID account) {
        // Past the shutdown save the session is released: its expiry gives the lease back
        if (saveEnded) return;
        for (Record record : records.values()) if (record.account.equals(account)) return;
        try {
            backend.releaseAccount(account, sessionId());
        } catch (SQLException e) {
            failures.warn(e, "Giving back the lease of a refused login failed, it runs out with the session");
        }
    }

    private boolean loadOrRefuse(Player player, long deadline, @Nullable BooleanSupplier claim) {
        final UUID account = player.getUuid();
        final ShardSession current = this.session;
        if (closing || (current != null && current.state() == ShardSession.State.ISOLATED))
            return refuse(player, "the shard is not taking logins");
        try {
            if (!loads.tryAcquire(remaining(deadline), TimeUnit.NANOSECONDS))
                return refuse(player, "the load queue did not clear within the budget");
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return refuse(player, "interrupted");
        }
        try {
            return loadWithin(player, account, deadline, claim);
        } catch (SQLException e) {
            failures.warn(e, "Loading a character failed: connection_id={}", player.getPlayerConnection().connectionId());
            return refuse(player, "the database failed");
        } finally {
            loads.release();
        }
    }

    private boolean loadWithin(Player player, UUID account, long deadline, @Nullable BooleanSupplier claim)
            throws SQLException {
        // The same account leaving this shard first: its logout save gives the lease back (§12.4 end)
        for (Record other : List.copyOf(records.values())) {
            if (!other.account.equals(account) || other.player == player) continue;
            try {
                other.gone.get(remaining(deadline), TimeUnit.NANOSECONDS);
            } catch (Exception _) {
                return refuse(player, "the previous session of this account did not let go in time");
            }
        }
        // The shutdown save may have started while this login waited for its load slot or for the
        // previous login of its account: from then on no ticket is claimed, no lease taken (§11.2)
        if (closing) return refuse(player, STOPPING);
        if (claim != null && !claim.getAsBoolean()) return false;
        boolean kickRequested = false;
        StoreBackend.LoadOutcome.Acquired acquired;
        while (true) {
            final long loadStart = System.nanoTime();
            final StoreBackend.LoadOutcome outcome;
            try {
                outcome = backend.load(account, player.getUsername(), sessionId());
            } finally {
                metrics.statement(StoreMetrics.Statement.LOAD, System.nanoTime() - loadStart);
            }
            if (outcome instanceof StoreBackend.LoadOutcome.Acquired taken) {
                acquired = taken;
                break;
            }
            final StoreBackend.LoadOutcome.Held held = (StoreBackend.LoadOutcome.Held) outcome;
            if (!displaceHolders) return refuse(player, "held by shard_id=" + held.shardId());
            // A connection that went away takes nobody's character: it would keep a reconnect of the
            // same account waiting behind it, and the holder would be kicked for nobody (§12.4 end)
            if (!player.isOnline())
                return refuse(player, "the connection closed while shard_id=" + held.shardId() + " held the character");
            if (!kickRequested) {
                if (!redis.requestKick(held.shardId(), account))
                    return refuse(player, "held by shard_id=" + held.shardId() + " and Redis cannot ask it to let go");
                kickRequested = true;
            }
            if (remaining(deadline) <= options.handoverPoll().toNanos())
                return refuse(player, "held by shard_id=" + held.shardId() + " past the handover budget");
            try {
                Thread.sleep(options.handoverPoll());
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return refuse(player, "interrupted");
            }
            // Nor between two polls: the holder may let go after the shutdown save started
            if (closing) return refuse(player, STOPPING);
        }
        final Record record = new Record(player, account, acquired.characterId(), acquired.version());
        final String undecodable = decode(record, acquired.sections());
        if (undecodable != null || remaining(deadline) <= 0) {
            releaseQuietly(record.characterId);
            return refuse(player, undecodable != null ? undecodable : "the load took longer than its budget");
        }
        // The shutdown save started while the acquire was under way: the lease goes back
        if (closing) {
            releaseQuietly(record.characterId);
            return refuse(player, STOPPING);
        }
        if (acquired.previousSession() != null) takeover(record, acquired.previousSession(), acquired.lastSavedAt());
        records.put(player, record);
        redis.presence(account, sessionId(), "loading");
        return true;
    }

    /** Decodes every registered section; the stored bytes are never touched on failure. */
    private @Nullable String decode(Record record, Map<String, StoreBackend.StoredSection> stored) {
        for (DataSection<?> section : sections.values()) {
            final StoreBackend.StoredSection found = stored.get(section.id());
            if (found == null) {
                record.values.put(section.id(), section.createDefault());
                continue;
            }
            if (found.schemaVersion() > section.schemaVersion()) {
                LOGGER.error("Refusing a login: character_id={} section_id={} is stored at schema_version={}, above the {} "
                        + "this build decodes", record.characterId, section.id(), found.schemaVersion(), section.schemaVersion());
                return "section " + section.id() + " is newer than this build";
            }
            try {
                final JsonElement json = JsonParser.parseString(found.json());
                record.values.put(section.id(), section.decode(json, found.schemaVersion()));
            } catch (RuntimeException e) {
                LOGGER.error("Refusing a login: character_id={} section_id={} cannot be decoded", record.characterId,
                        section.id(), e);
                return "section " + section.id() + " cannot be decoded";
            }
        }
        return null;
    }

    private void takeover(Record record, long previousSession, @Nullable Instant lastSavedAt) {
        final Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("character_id", record.characterId);
        attributes.put("previous_session_id", previousSession);
        attributes.put("gap_ms", lastSavedAt == null ? null : Duration.between(lastSavedAt, Instant.now()).toMillis());
        analytics.accept("lease_takeover", attributes);
    }

    /**
     * {@code loading → online}: the admission provider let the player in. Once the shutdown save
     * has started the player is refused instead ({@code loading → offline}): the save froze
     * everybody online without it, so it would play and be written outside of that save.
     *
     * @param player the player
     * @return false if the player was refused, which kicked it; true otherwise, also when there
     *         was nothing to let in because the player had already left
     */
    public boolean admitted(Player player) {
        final Record record = records.get(player);
        if (record == null) return true;
        final boolean stopping;
        synchronized (admissions) {
            stopping = closing;
            if (!stopping) {
                synchronized (record) {
                    // Abandoned meanwhile by its logout
                    if (record.state != LeaseState.LOADING) return true;
                    record.state = LeaseState.ONLINE;
                }
            }
        }
        if (stopping) {
            if (!abandon(record)) return true;
            refuse(player, STOPPING);
            return false;
        }
        autosaveOrder.add(record);
        redis.presence(record.account, sessionId(), "online");
        return true;
    }

    /**
     * A loaded player that is not going to play: the admission provider refused it, or its
     * connection went away before it got in. The lease goes back without any write.
     *
     * @param player the player
     */
    public void abandon(Player player) {
        final Record record = records.get(player);
        if (record != null) abandon(record);
    }

    /** {@code loading → offline} without a write; false if it was no longer loading: admitted, or abandoned already. */
    private boolean abandon(Record record) {
        synchronized (record) {
            if (record.state != LeaseState.LOADING) return false;
            record.state = LeaseState.OFFLINE;
        }
        // Released while still listed: a login of the same account that comes meanwhile waits for
        // this one to be gone before it takes the lease under this session, so this release cannot
        // give back the lease that login takes (§11.3 end)
        releaseQuietly(record.characterId);
        finished(record, LeaseState.OFFLINE);
        return true;
    }

    private static boolean refuse(Player player, String cause) {
        LOGGER.info("Refusing a login: connection_id={} reason={} cause={}", player.getPlayerConnection().connectionId(),
                DisconnectKeys.STATE_UNAVAILABLE, cause);
        player.kick(Component.translatable(DisconnectKeys.STATE_UNAVAILABLE));
        return false;
    }

    // ---- what a plugin sees ----

    <T> T get(Player player, DataSection<T> section) {
        final Record record = records.get(player);
        if (record == null) throw new IllegalStateException(player.getUsername() + " has no data loaded");
        final Object value = record.values.get(section.id());
        if (value == null) throw new IllegalStateException("data section " + section.id() + " is not loaded");
        @SuppressWarnings("unchecked") final T typed = (T) value;
        return typed;
    }

    void markDirty(Player player, DataSection<?> section) {
        final Record record = records.get(player);
        if (record != null) record.dirty.add(section.id());
    }

    boolean isFrozen(Player player) {
        final ShardSession current = this.session;
        if (closing) return true;
        if (current != null && (current.state() == ShardSession.State.DEGRADED || current.state() == ShardSession.State.CLOSING))
            return true;
        final Record record = records.get(player);
        return record != null && (record.state == LeaseState.SAVING || record.state == LeaseState.IN_TRANSIT);
    }

    CompletableFuture<SaveResult> saveNow(Player player) {
        final long start = System.nanoTime();
        final Record record = records.get(player);
        if (record == null || record.state != LeaseState.ONLINE || isFrozen(player)) {
            final CompletableFuture<SaveResult> frozen = CompletableFuture.completedFuture(SaveResult.UNAVAILABLE);
            critical("save_now", SaveResult.UNAVAILABLE, "frozen", start, 1);
            return frozen;
        }
        final CompletableFuture<SaveResult> done = new CompletableFuture<>();
        submit(record, snapshot(record), false, done);
        return done.whenComplete((result, _) -> critical("save_now", result, result == SaveResult.SAVED ? null
                : result == SaveResult.FENCED ? "version_mismatch" : "db_error", start, 1));
    }

    /**
     * How a {@link #saveOnline()} ended.
     *
     * @param saved  the players whose save was written
     * @param online the players online when it started
     */
    public record SaveCount(int saved, int online) {
    }

    /**
     * Saves every online player now, for the console command {@code save}
     * ({@code docs/DESIGN-PROD.md} {@code API-PROD-011}): each snapshot is taken on the tick thread
     * that owns the player, like an autosave, and none of them is a critical commit. A frozen
     * player, one that leaves before its tick comes, and a save not over within
     * {@value #SAVE_ONLINE_BUDGET_SECONDS} s count as not saved.
     *
     * @return the count, completed once every save is over
     */
    public CompletableFuture<SaveCount> saveOnline() {
        final List<CompletableFuture<SaveResult>> saves = new ArrayList<>();
        for (Record record : records.values()) {
            if (record.state != LeaseState.ONLINE) continue;
            final CompletableFuture<SaveResult> done = new CompletableFuture<>();
            saves.add(done.completeOnTimeout(SaveResult.UNAVAILABLE, SAVE_ONLINE_BUDGET_SECONDS, TimeUnit.SECONDS));
            record.player.scheduler().scheduleNextTick(() -> {
                if (record.state == LeaseState.ONLINE && !isFrozen(record.player)) submit(record, snapshot(record), false, done);
                else done.complete(SaveResult.UNAVAILABLE);
            });
        }
        return CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new)).thenApply(_ -> new SaveCount(
                (int) saves.stream().filter(save -> save.join() == SaveResult.SAVED).count(), saves.size()));
    }

    CompletableFuture<SaveResult> commitTogether(Map<Player, Map<DataSection<?>, Object>> staged, Runnable apply) {
        final long start = System.nanoTime();
        final List<Record> participants = new ArrayList<>();
        for (Player player : staged.keySet()) {
            final Record record = records.get(player);
            if (record == null || record.state != LeaseState.ONLINE || isFrozen(player)) {
                critical("commit", SaveResult.UNAVAILABLE, "frozen", start, staged.size());
                return CompletableFuture.completedFuture(SaveResult.UNAVAILABLE);
            }
            participants.add(record);
        }
        // Encoded on the caller's thread, which is the tick thread of the operation
        final Map<Record, Map<String, StoreBackend.StoredSection>> encoded = new HashMap<>();
        for (Record record : participants) {
            final Map<String, StoreBackend.StoredSection> out = new HashMap<>();
            for (Map.Entry<DataSection<?>, Object> entry : staged.get(record.player).entrySet())
                out.put(entry.getKey().id(), encode(entry.getKey(), entry.getValue()));
            encoded.put(record, out);
        }
        final CompletableFuture<SaveResult> done = new CompletableFuture<>();
        Thread.ofVirtual().name("Syn-Store-Commit").start(() -> {
            final List<Record> ordered = participants.stream().sorted(Comparator.comparingLong(r -> r.characterId)).toList();
            for (Record record : ordered) record.writeLock.lock();
            try {
                final List<StoreBackend.Write> writes = ordered.stream()
                        .map(r -> new StoreBackend.Write(r.characterId, r.version.get(), encoded.get(r), false)).toList();
                final long writeStart = System.nanoTime();
                try {
                    backend.write(sessionId(), writes);
                } finally {
                    metrics.statement(StoreMetrics.Statement.COMMIT, System.nanoTime() - writeStart);
                }
                for (Record record : ordered) {
                    record.version.incrementAndGet();
                    // Values first, then the count: a snapshot that read the count before it
                    // read one of these values is dropped at its write, never kept with old ones
                    for (Map.Entry<DataSection<?>, Object> entry : staged.get(record.player).entrySet())
                        record.values.put(entry.getKey().id(), entry.getValue());
                    final long commit = record.commits.incrementAndGet();
                    for (DataSection<?> section : staged.get(record.player).keySet())
                        record.committedAt.put(section.id(), commit);
                }
                ordered.getFirst().player.scheduler().scheduleNextTick(apply);
                critical("commit", SaveResult.SAVED, null, start, ordered.size());
                metrics.saved(SaveResult.SAVED);
                done.complete(SaveResult.SAVED);
            } catch (StoreBackend.FencedException e) {
                failures.warn("A commit was fenced: character_id={}", e.characterId());
                critical("commit", SaveResult.FENCED, "version_mismatch", start, ordered.size());
                metrics.saved(SaveResult.FENCED);
                done.complete(SaveResult.FENCED);
            } catch (SQLException e) {
                failures.warn(e, "A commit failed: participants={}", ordered.size());
                critical("commit", SaveResult.UNAVAILABLE, "db_error", start, ordered.size());
                metrics.saved(SaveResult.UNAVAILABLE);
                done.complete(SaveResult.UNAVAILABLE);
            } finally {
                for (Record record : ordered) record.writeLock.unlock();
            }
        });
        return done;
    }

    private void critical(String kind, SaveResult result, @Nullable String cause, long startNanos, int participants) {
        final Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("shard_id", shardId);
        attributes.put("kind", kind);
        attributes.put("result", result.name().toLowerCase(Locale.ROOT));
        attributes.put("cause", cause);
        attributes.put("elapsed_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
        attributes.put("participants", participants);
        analytics.accept("critical_commit", attributes);
    }

    /**
     * What a transfer hands to the target shard: the character, and the version its release wrote.
     *
     * @param account     the account
     * @param characterId the character
     * @param version     the version after the write that gave the lease back
     */
    public record Handoff(UUID account, long characterId, long version) {
    }

    /**
     * {@code online → saving → in_transit} for a transfer ({@code docs/DESIGN-PROD.md} §11.3,
     * {@code API-PROD-028} issuing ③ and ④): freezes the player, snapshots it on its tick thread
     * and writes it, giving the lease back in the same transaction. One attempt: a failed write
     * sends the player back to {@code online}.
     *
     * @param player the player to transfer
     * @return null if the player is not online here; otherwise a future of the handoff, completing
     *         with null if the write failed
     */
    public @Nullable CompletableFuture<@Nullable Handoff> transferOut(Player player) {
        final Record record = records.get(player);
        if (record == null || closing) return null;
        final CompletableFuture<SaveResult> done = new CompletableFuture<>();
        synchronized (record) {
            if (record.state != LeaseState.ONLINE) return null;
            record.state = LeaseState.SAVING;
            record.transferAsked = done;
        }
        player.scheduler().scheduleNextTick(() -> {
            synchronized (record) {
                // Logged out before this tick: the logout save took the snapshot instead
                if (record.transferAsked == null) return;
                record.transferAsked = null;
                record.transferring = true;
            }
            submit(record, snapshot(record), true, done);
        });
        return done.thenApply(result -> result == SaveResult.SAVED
                ? new Handoff(record.account, record.characterId, record.version.get()) : null);
    }

    // ---- saving ----

    /** Encodes the dirty sections, on the tick thread that owns the player. */
    private Map<String, Encoded> snapshot(Record record) {
        final long takenAt = record.commits.get();
        final Map<String, Encoded> out = new HashMap<>();
        for (String id : List.copyOf(record.dirty)) {
            record.dirty.remove(id);
            final DataSection<?> section = sections.get(id);
            final Object value = record.values.get(id);
            if (section == null || value == null) continue;
            try {
                out.put(id, new Encoded(encode(section, value), takenAt));
            } catch (RuntimeException e) {
                LOGGER.error("Encoding a section failed, it stays dirty: character_id={} section_id={}",
                        record.characterId, id, e);
                record.dirty.add(id);
            }
        }
        return out;
    }

    private static StoreBackend.StoredSection encode(DataSection<?> section, Object value) {
        @SuppressWarnings("unchecked") final DataSection<Object> typed = (DataSection<Object>) section;
        return new StoreBackend.StoredSection(section.schemaVersion(), typed.encode(value).toString());
    }

    /**
     * Queues a snapshot on the character's single write slot; a snapshot already waiting there is
     * merged under it, the newer value of a section winning.
     */
    private void submit(Record record, Map<String, Encoded> snapshot, boolean release,
                        @Nullable CompletableFuture<SaveResult> done) {
        synchronized (record) {
            if (record.pending == null) record.pending = new HashMap<>(snapshot);
            else record.pending.putAll(snapshot);
            if (release) record.release = true;
            if (done != null) record.waiting.add(done);
            if (record.writing) return;
            record.writing = true;
        }
        Thread.ofVirtual().name("Syn-Store-Write").start(() -> drain(record));
    }

    private void drain(Record record) {
        int failed = 0;
        long firstFailure = 0;
        while (true) {
            final Map<String, Encoded> snapshot;
            final boolean release;
            final boolean transfer;
            final List<CompletableFuture<SaveResult>> waiting;
            synchronized (record) {
                if (record.pending == null || record.state == LeaseState.FENCED) {
                    record.writing = false;
                    record.pending = null;
                    // Only a fenced character can have callers waiting without a snapshot to write
                    for (CompletableFuture<SaveResult> future : record.waiting) future.complete(SaveResult.FENCED);
                    record.waiting.clear();
                    return;
                }
                snapshot = record.pending;
                release = record.release;
                transfer = record.transferring;
                waiting = List.copyOf(record.waiting);
                record.pending = null;
                record.waiting.clear();
            }
            final SaveResult result = write(record, snapshot, release);
            metrics.saved(result);
            if (transfer && result == SaveResult.UNAVAILABLE) {
                // saving → online: a transfer gets one attempt, the player stays and the snapshot
                // waits for the next save as an ordinary one. A player that logged out meanwhile
                // stays in saving: the snapshot is retried as its logout save
                synchronized (record) {
                    record.transferring = false;
                    if (!record.loggedOut) {
                        record.release = false;
                        record.state = LeaseState.ONLINE;
                    }
                }
                for (CompletableFuture<SaveResult> future : waiting) future.complete(result);
            } else if (result != SaveResult.UNAVAILABLE || !release) {
                for (CompletableFuture<SaveResult> future : waiting) future.complete(result);
            } else {
                // A save that gives the lease back is only over once it made it or was given up:
                // the shutdown waits on it, and a first failure is not an answer yet
                synchronized (record) {
                    record.waiting.addAll(waiting);
                }
            }
            switch (result) {
                case SAVED -> {
                    failed = 0;
                    if (release && transfer) {
                        final boolean left;
                        synchronized (record) {
                            record.writing = false;
                            left = record.loggedOut;
                            // saving → in_transit: the lease is given back, the ticket carries the version
                            if (!left) record.state = LeaseState.IN_TRANSIT;
                        }
                        // saving → offline: the player logged out while the write was under way
                        if (left) finished(record, LeaseState.OFFLINE);
                        else redis.presence(record.account, sessionId(), "in_transit");
                        return;
                    }
                    if (release) {
                        finished(record, LeaseState.OFFLINE);
                        synchronized (record) {
                            record.writing = false;
                        }
                        return;
                    }
                }
                case FENCED -> {
                    fence(record, "zero_rows");
                    synchronized (record) {
                        record.writing = false;
                        record.pending = null;
                    }
                    return;
                }
                case UNAVAILABLE -> {
                    final long now = System.nanoTime();
                    if (failed++ == 0) firstFailure = now;
                    if (release && !transfer && (saveEnded || now - firstFailure >= options.giveUpAfter().toNanos())) {
                        orphan(record);
                        synchronized (record) {
                            record.writing = false;
                            record.pending = null;
                            for (CompletableFuture<SaveResult> future : record.waiting) future.complete(SaveResult.UNAVAILABLE);
                            record.waiting.clear();
                        }
                        return;
                    }
                    // Kept for the next attempt, under whatever came in meanwhile
                    synchronized (record) {
                        if (record.pending != null) {
                            final Map<String, Encoded> newer = record.pending;
                            record.pending = new HashMap<>(snapshot);
                            record.pending.putAll(newer);
                        } else {
                            record.pending = new HashMap<>(snapshot);
                        }
                    }
                    final List<Duration> delays = options.retryDelays();
                    sleep(delays.get(Math.min(failed - 1, delays.size() - 1)));
                }
            }
        }
    }

    private SaveResult write(Record record, Map<String, Encoded> snapshot, boolean release) {
        final ShardSession current = this.session;
        if (current != null && current.state() == ShardSession.State.ISOLATED) return SaveResult.FENCED;
        // Nothing changed and nothing to give back: no write, no version bump
        if (snapshot.isEmpty() && !release) return SaveResult.SAVED;
        record.writeLock.lock();
        try {
            final Map<String, StoreBackend.StoredSection> sections = new HashMap<>();
            for (Map.Entry<String, Encoded> entry : snapshot.entrySet()) {
                if (entry.getValue().takenAt() >= record.committedAt.getOrDefault(entry.getKey(), 0L)) {
                    sections.put(entry.getKey(), entry.getValue().section());
                } else {
                    // A commit wrote this section after the snapshot read it. Dirty again, so a
                    // change made on top of the commit before the next snapshot is not lost
                    record.dirty.add(entry.getKey());
                }
            }
            if (sections.isEmpty() && !release) return SaveResult.SAVED;
            final long writeStart = System.nanoTime();
            try {
                backend.write(sessionId(), List.of(new StoreBackend.Write(record.characterId, record.version.get(), sections, release)));
            } finally {
                metrics.statement(StoreMetrics.Statement.SAVE, System.nanoTime() - writeStart);
            }
            record.version.incrementAndGet();
            return SaveResult.SAVED;
        } catch (StoreBackend.FencedException _) {
            return SaveResult.FENCED;
        } catch (SQLException e) {
            failures.warn(e, "Saving a character failed: character_id={} release={}", record.characterId, release);
            return SaveResult.UNAVAILABLE;
        } finally {
            record.writeLock.unlock();
        }
    }

    /** {@code online → fenced}: this shard no longer holds the character; the player goes. */
    private void fence(Record record, String cause) {
        if (record.state == LeaseState.FENCED || record.state == LeaseState.OFFLINE || record.state == LeaseState.ORPHANED)
            return;
        record.state = LeaseState.FENCED;
        final Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("character_id", record.characterId);
        attributes.put("session_id", sessionId());
        attributes.put("cause", cause);
        analytics.accept("save_fenced", attributes);
        LOGGER.error("Character fenced, kicking its player: character_id={} cause={}", record.characterId, cause);
        records.remove(record.player, record);
        record.gone.complete(null);
        record.player.kick(Component.translatable(DisconnectKeys.STATE_UNAVAILABLE));
    }

    /** {@code saving → orphaned}: the logout save could not be written; the lease goes back anyway. */
    private void orphan(Record record) {
        LOGGER.error("Dropping the logout save of character_id={}: it could not be written within {} s. The last "
                + "saved version stands.", record.characterId, options.giveUpAfter().toSeconds());
        releaseQuietly(record.characterId);
        finished(record, LeaseState.ORPHANED);
    }

    private void finished(Record record, LeaseState state) {
        record.state = state;
        records.remove(record.player, record);
        redis.clearPresence(record.account);
        record.gone.complete(null);
    }

    private void releaseQuietly(long characterId) {
        // Past the shutdown save the session is released: its expiry gives the lease back
        if (saveEnded) return;
        try {
            backend.release(characterId, sessionId());
        } catch (SQLException e) {
            failures.warn(e, "Giving a lease back failed, it runs out with the session: character_id={}", characterId);
        }
    }

    /**
     * Logout: {@code online → saving}, write the dirty sections and give the lease back. A transfer
     * still writing becomes the logout save: its write failing is retried as one, its write
     * succeeding ends in {@code offline}. A player the shutdown save froze before its snapshot
     * was taken has that snapshot taken here, during the save phase.
     */
    private void loggedOut(Player player) {
        final Record record = records.get(player);
        if (record == null) return;
        // Admitted meanwhile, it goes on to the logout save below
        if (record.state == LeaseState.LOADING && abandon(record)) return;
        final CompletableFuture<SaveResult> shutdownSave;
        final CompletableFuture<SaveResult> droppedTransfer;
        synchronized (record) {
            // Under the lock saveAll freezes a player under: frozen before, this disconnect takes
            // the shutdown snapshot below; frozen after, the freeze found it saving and left it out
            shutdownSave = shutdownSaves.get(record);
            if (shutdownSave != null) droppedTransfer = null;
            else switch (record.state) {
                case ONLINE -> {
                    record.state = LeaseState.SAVING;
                    submit(record, snapshot(record), true, null);
                    return;
                }
                case IN_TRANSIT -> {
                    // Written and released before the ticket was sent; the presence runs out on its own
                    records.remove(player, record);
                    record.gone.complete(null);
                    return;
                }
                case SAVING -> {
                    if (record.transferring) {
                        record.loggedOut = true;
                        return;
                    }
                    // Saving already (shutdown, a previous logout) unless a transfer is not snapshotted yet
                    droppedTransfer = record.transferAsked;
                    if (droppedTransfer == null) return;
                    record.transferAsked = null;
                    submit(record, snapshot(record), true, null);
                }
                default -> {
                    // No longer this shard's
                    return;
                }
            }
        }
        if (shutdownSave != null) {
            // Nothing ticks a player that never entered the world: the snapshot the shutdown save
            // handed to its scheduler would never run
            if (!saveEnded) shutdownSnapshot(record, shutdownSave, true);
            return;
        }
        droppedTransfer.complete(SaveResult.UNAVAILABLE);
    }

    /**
     * One tick of {@code store.autosave}: online ÷ period players on average, the remainder carried
     * to the next tick, so each player comes round once per period; each on its own tick thread.
     */
    private void autosaveTick() {
        final ShardSession current = this.session;
        if (closing || (current != null && current.state() != ShardSession.State.ACTIVE)) return;
        autosaveCredit += autosaveOrder.size();
        final long perTick = autosaveCredit / options.autosavePeriodTicks();
        autosaveCredit %= options.autosavePeriodTicks();
        for (long i = 0; i < perTick; i++) {
            final Record record = autosaveOrder.poll();
            if (record == null) return;
            if (record.state != LeaseState.ONLINE) {
                // A transfer whose write fails goes back to online: it keeps its turn
                if (record.state == LeaseState.SAVING) autosaveOrder.add(record);
                continue;
            }
            autosaveOrder.add(record);
            record.player.scheduler().scheduleNextTick(() -> {
                if (record.state == LeaseState.ONLINE) submit(record, snapshot(record), false, null);
            });
        }
    }

    private void refreshPresence() {
        final Map<UUID, String> online = new HashMap<>();
        for (Record record : records.values())
            online.put(record.account, record.state == LeaseState.LOADING ? "loading" : "online");
        redis.presence(online, sessionId());
    }

    private void kickRequested(UUID account, String requesterShard) {
        for (Record record : records.values()) {
            if (!record.account.equals(account) || record.state != LeaseState.ONLINE) continue;
            LOGGER.info("Letting a player go for a login on shard_id={}: character_id={}", requesterShard, record.characterId);
            record.player.kick(DisconnectReasons.LOGGED_IN_ELSEWHERE);
        }
    }

    // ---- shutdown (API-PROD-007) ----

    @Override
    public void onGateClosed(Duration countdown) {
        if (countdown.isZero()) return;
        final Component notice = Component.translatable("synaxis:server-stopping-in", Component.text(countdown.toSeconds()));
        // A play packet cannot go to a connection still configuring: its write thread throws and
        // drops the connection without a reason, and the player out of the shutdown save. That
        // thread turns the state to play once it wrote FinishConfiguration, so what is queued
        // after it is written as play
        for (Record record : records.values()) {
            if (record.player.getPlayerConnection().getServerState() == ConnectionState.PLAY)
                record.player.sendMessage(notice);
        }
    }

    @Override
    public CompletableFuture<?> saveAll(Duration budget, boolean tickAlive) {
        if (!closing) {
            synchronized (admissions) {
                closing = true;
            }
            shutdownStartedNanos = System.nanoTime();
            final ShardSession current = this.session;
            if (current != null) current.beginClosing();
            // Under the lock a disconnect decides under (loggedOut): a player whose logout save
            // came first is saving already and left out, its logout save goes on alone
            for (Record record : records.values()) {
                synchronized (record) {
                    if (record.state != LeaseState.ONLINE) continue;
                    record.state = LeaseState.SAVING;
                    shutdownSaves.put(record, new CompletableFuture<>());
                }
            }
            shutdownOnline = shutdownSaves.size();
            if (tickAlive) process.scheduler().scheduleNextTick(this::disconnectOutsideTheWorld);
        }
        for (Map.Entry<Record, CompletableFuture<SaveResult>> entry : shutdownSaves.entrySet()) {
            final Record record = entry.getKey();
            if (tickAlive) {
                record.player.scheduler().scheduleNextTick(() -> {
                    // A tick late past the save phase takes nothing more
                    if (!saveEnded) shutdownSnapshot(record, entry.getValue(), true);
                });
            } else if (!record.shutdownSnapshot.get()) {
                if (directSnapshotWarned.compareAndSet(false, true))
                    LOGGER.warn("The tick is stuck: the shutdown save reads the players' data on the shutdown thread, "
                            + "what a plugin did not write into its sections is lost");
                shutdownSnapshot(record, entry.getValue(), false);
            }
        }
        return CompletableFuture.allOf(shutdownSaves.values().toArray(CompletableFuture[]::new));
    }

    /**
     * Disconnects every frozen player that is in no instance, on the tick scheduler thread: nothing
     * ticks it, so the snapshot handed to its scheduler would wait for a client that may never
     * finish its configuration. Its disconnect takes the snapshot instead ({@link #loggedOut}), on
     * this thread for a player still configuring, on this tick's {@code updateWaitingPlayers} for
     * one in the play state.
     */
    private void disconnectOutsideTheWorld() {
        for (Record record : shutdownSaves.keySet()) {
            final Player player = record.player;
            if (player.isOnline() && player.getInstance() == null) player.kick(DisconnectReasons.SERVER_STOPPING);
        }
    }

    private void shutdownSnapshot(Record record, CompletableFuture<SaveResult> done, boolean runHooks) {
        if (!record.shutdownSnapshot.compareAndSet(false, true)) return;
        if (runHooks) {
            final ShutdownNotice notice = () -> record.player;
            for (Hook hook : shutdownHooks) {
                try {
                    hook.hook().accept(notice);
                } catch (Throwable throwable) {
                    LOGGER.error("Shutdown save hook failed: plugin_id={}", hook.pluginId(), throwable);
                }
            }
        }
        submit(record, snapshot(record), true, done);
    }

    @Override
    public void onSaveEnded(boolean timedOut) {
        saveEnded = true;
        int saved = 0;
        for (Map.Entry<Record, CompletableFuture<SaveResult>> entry : shutdownSaves.entrySet()) {
            final CompletableFuture<SaveResult> future = entry.getValue();
            if (future.isDone() && future.getNow(SaveResult.UNAVAILABLE) == SaveResult.SAVED) saved++;
            else LOGGER.error("Not saved on shutdown, its lease runs out with the session: character_id={}",
                    entry.getKey().characterId);
        }
        final Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("shard_id", shardId);
        attributes.put("online", shutdownOnline);
        attributes.put("saved", saved);
        attributes.put("failed", shutdownOnline - saved);
        attributes.put("elapsed_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - shutdownStartedNanos));
        attributes.put("timed_out", timedOut);
        analytics.accept("shutdown_saved", attributes);
        final ShardSession current = this.session;
        if (current != null) current.release();
    }

    // ---- helpers ----

    String shardId() {
        return shardId;
    }

    private long sessionId() {
        final ShardSession current = this.session;
        return current == null ? 0 : current.sessionId();
    }

    private static long remaining(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * How many players this store holds data for.
     *
     * @return the count, for tests and diagnostics
     */
    int loadedCount() {
        return records.size();
    }

    /**
     * Closes Redis and the session's renewal; the pool belongs to whoever opened it.
     */
    public void close() {
        redis.close();
        final ShardSession current = this.session;
        if (current != null) current.close();
    }

    /**
     * The per plugin view of this store.
     *
     * @param pluginId the plugin
     * @return its {@code PlayerDataStore}
     */
    public PluginPlayerData forPlugin(String pluginId) {
        return new PluginPlayerData(pluginId, this);
    }

    /**
     * The version the next write of a player's character is based on.
     *
     * @return the version, -1 if the player is not loaded
     */
    long versionOf(Player player) {
        final Record record = records.get(player);
        return record == null ? -1 : record.version.get();
    }

    /**
     * Whether a player's write slot is busy: a write under way or a snapshot waiting for it.
     *
     * @return {@code false} once every snapshot taken so far is written or given up
     */
    boolean isWriting(Player player) {
        final Record record = records.get(player);
        if (record == null) return false;
        synchronized (record) {
            return record.writing;
        }
    }
}
