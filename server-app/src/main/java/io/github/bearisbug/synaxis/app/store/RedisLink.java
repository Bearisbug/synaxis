package io.github.bearisbug.synaxis.app.store;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.bearisbug.synaxis.plugin.LogBudget;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.ConnectionPoolConfig;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.params.SetParams;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * What a shard keeps in Redis, all of it disposable ({@code docs/DESIGN-PROD.md} {@code API-PROD-006},
 * {@code ADR-PROD-003}): where a player is ({@code presence:{uuid}}, a routing hint only) and the
 * requests to let a player go ({@code synaxis:kick:{shard_id}}), the accounts whose roles changed
 * ({@code synaxis:permission}) and the changes of the bans and the whitelist ({@code synaxis:moderation}).
 * <p>
 * Nothing here decides who holds a character, PostgreSQL does; Redis being down only turns a
 * cross-shard duplicate login into a refusal. Its failures are counted into one warning per 10 s.
 * The presence writes are queued for a thread of their own, so a Redis that does not answer holds
 * up no login, admission or logout.
 */
public final class RedisLink implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisLink.class);

    /** How long a presence key lives without being refreshed. */
    static final long PRESENCE_TTL_MS = 30_000;
    static final int TIMEOUT_MS = 2_000;
    /** How many presence writes wait for their thread at most; past it the oldest one is dropped. */
    static final int PRESENCE_QUEUE = 4_096;
    /** Where a role change is announced, with the account it changed. */
    static final String PERMISSION_CHANNEL = "synaxis:permission";
    /** Where a change of the bans or the whitelist is announced. */
    static final String MODERATION_CHANNEL = "synaxis:moderation";
    private static final long RESUBSCRIBE_MILLIS = 1_000;

    /** Asked to let a player go because the same account logs in on another shard. */
    @FunctionalInterface
    public interface KickListener {
        void kickRequested(UUID account, String requesterShard);
    }

    private final @Nullable RedisClient client;
    private final String shardId;
    private final LogBudget failures = new LogBudget(LOGGER, "Redis failures");
    private final LogBudget droppedPresence = new LogBudget(LOGGER, "presence writes dropped");
    private final AtomicBoolean closed = new AtomicBoolean();
    private final List<JedisPubSub> subscriptions = new CopyOnWriteArrayList<>();
    // One thread: the writes of an account run in the order they came, loading, online, cleared
    private final @Nullable ExecutorService presenceWrites;
    private volatile boolean up;

    private RedisLink(@Nullable RedisClient client, String shardId) {
        this.client = client;
        this.shardId = shardId;
        this.presenceWrites = client == null ? null : new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(PRESENCE_QUEUE), Thread.ofPlatform().name("Syn-Presence-Write").daemon().factory(),
                this::dropOldest);
    }

    /**
     * A shard that runs without Redis: every request fails, as if Redis were down.
     *
     * @param shardId the id of this shard
     * @return the link
     */
    public static RedisLink disabled(String shardId) {
        return new RedisLink(null, shardId);
    }

    /**
     * Connects to Redis lazily: an unreachable Redis at start is a warning, not a refusal.
     *
     * @param url     {@code redis://[user:password@]host:port[/database]}, every character of the
     *                password but letters, digits and {@code -._~} percent-encoded
     * @param shardId the id of this shard, the name of its kick channel
     * @return the link
     * @throws IllegalArgumentException if the URL is not one, naming the variable but not the URL
     */
    public static RedisLink connect(String url, String shardId) {
        final URI uri;
        final @Nullable Integer database;
        try {
            uri = URI.create(url);
            final String path = uri.getPath();
            database = path == null || path.length() <= 1 ? null : Integer.parseInt(path.substring(1));
        } catch (IllegalArgumentException _) {
            throw notARedisUrl();
        }
        // A '/' or '#' in the password leaves an authority without a host, the rest of the password
        // in the path or the fragment
        if (uri.getHost() == null) throw notARedisUrl();
        final DefaultJedisClientConfig.Builder config = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(TIMEOUT_MS)
                .socketTimeoutMillis(TIMEOUT_MS)
                .clientName("synaxis-" + shardId);
        final String userInfo = uri.getUserInfo();
        if (userInfo != null) {
            final int colon = userInfo.indexOf(':');
            if (colon < 0) config.password(userInfo);
            else {
                if (colon > 0) config.user(userInfo.substring(0, colon));
                config.password(userInfo.substring(colon + 1));
            }
        }
        if (database != null) config.database(database);
        // Every subscription keeps a pooled connection while it listens: a request finding all of
        // them taken fails after the timeout instead of waiting for one forever
        final ConnectionPoolConfig pool = new ConnectionPoolConfig();
        pool.setMaxWait(Duration.ofMillis(TIMEOUT_MS));
        final RedisClient client = RedisClient.builder()
                .hostAndPort(new HostAndPort(uri.getHost(), uri.getPort() < 0 ? 6379 : uri.getPort()))
                .clientConfig(config.build())
                .poolConfig(pool)
                .build();
        return new RedisLink(client, shardId);
    }

    /**
     * The refusal of a URL that is not one. It names the variable only and has no cause: what
     * {@link URI} and {@link Integer#parseInt} say about the URL quotes it, password and all, and the
     * refusal to start is logged with its whole cause chain.
     */
    private static IllegalArgumentException notARedisUrl() {
        return new IllegalArgumentException(StoreSettings.REDIS_URL + " is not redis://[user:password@]host:port"
                + "[/database]; percent-encode every character of the password but letters, digits and -._~");
    }

    /**
     * Starts listening on this shard's kick channel, on a thread of its own that resubscribes
     * every second while Redis is down.
     *
     * @param listener told about every kick request
     */
    public void listen(KickListener listener) {
        subscribe("Syn-Redis-Kick", kickChannel(shardId), message -> deliver(message, listener));
    }

    /**
     * Starts listening on {@value #PERMISSION_CHANNEL}, the same way as {@link #listen}.
     *
     * @param listener told the account of every role change
     */
    public void listenPermissions(Consumer<UUID> listener) {
        subscribe("Syn-Redis-Permission", PERMISSION_CHANNEL, message -> {
            try {
                listener.accept(UUID.fromString(JsonParser.parseString(message).getAsJsonObject()
                        .get("account_uuid").getAsString()));
            } catch (RuntimeException e) {
                failures.warn(e, "Ignoring a malformed role change on {}", PERMISSION_CHANNEL);
            }
        });
    }

    /**
     * Starts listening on {@value #MODERATION_CHANNEL}, the same way as {@link #listen}. What changed
     * is not handed on: every change is answered by reading the bans and the whitelist again.
     *
     * @param listener told about every change
     */
    public void listenModeration(Runnable listener) {
        subscribe("Syn-Redis-Moderation", MODERATION_CHANNEL, _ -> listener.run());
    }

    /**
     * Starts listening on the world state flag channel of a region ({@code docs/DESIGN-M3.md}
     * {@code API-M3-013}), the same way as {@link #listen}.
     *
     * @param channel  {@code synaxis:worldflag:<map_id>:<region_id>}
     * @param listener told every message as it came
     */
    public void listenWorldFlags(String channel, Consumer<String> listener) {
        subscribe("Syn-Redis-WorldFlag", channel, listener);
    }

    /**
     * Announces a world state flag written ({@code docs/DESIGN-M3.md} {@code API-M3-013}).
     *
     * @param channel {@code synaxis:worldflag:<map_id>:<region_id>}
     * @param message the flag key and its new version
     * @return false if the message could not be sent: Redis is down, or not configured
     */
    public boolean publishWorldFlag(String channel, String message) {
        final RedisClient redis = this.client;
        if (redis == null) return false;
        try {
            redis.publish(channel, message);
            up = true;
            return true;
        } catch (RuntimeException e) {
            failed(e, "publish");
            return false;
        }
    }

    private void subscribe(String threadName, String channel, Consumer<String> handler) {
        final RedisClient redis = this.client;
        if (redis == null) return;
        Thread.ofPlatform().name(threadName).daemon().start(() -> {
            while (!closed.get()) {
                final JedisPubSub pubSub = new JedisPubSub() {
                    @Override
                    public void onMessage(String subscribed, String message) {
                        handler.accept(message);
                    }
                };
                subscriptions.add(pubSub);
                try {
                    redis.subscribe(pubSub, channel);
                } catch (RuntimeException e) {
                    if (closed.get()) return;
                    failed(e, "subscribe");
                } finally {
                    subscriptions.remove(pubSub);
                }
                try {
                    Thread.sleep(RESUBSCRIBE_MILLIS);
                } catch (InterruptedException _) {
                    return;
                }
            }
        });
    }

    private void deliver(String message, KickListener listener) {
        try {
            final JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            listener.kickRequested(UUID.fromString(json.get("uuid").getAsString()),
                    json.get("requester_shard").getAsString());
        } catch (RuntimeException e) {
            failures.warn(e, "Ignoring a malformed kick request on {}", kickChannel(shardId));
        }
    }

    /**
     * Asks the shard holding a character to let its player go ({@code §12.4}).
     *
     * @param holderShard the shard to ask
     * @param account     the account that logs in here
     * @return false if the request could not be sent: Redis is down, or not configured
     */
    public boolean requestKick(String holderShard, UUID account) {
        final RedisClient redis = this.client;
        if (redis == null) return false;
        final JsonObject message = new JsonObject();
        message.addProperty("uuid", account.toString());
        message.addProperty("requester_shard", shardId);
        message.addProperty("reason", "logged-in-elsewhere");
        try {
            redis.publish(kickChannel(holderShard), message.toString());
            up = true;
            return true;
        } catch (RuntimeException e) {
            failed(e, "publish");
            return false;
        }
    }

    /**
     * Tells every shard that an account's roles changed ({@code API-PROD-006}).
     *
     * @param account the account
     * @return false if the message could not be sent: Redis is down, or not configured
     */
    public boolean publishPermissionChange(UUID account) {
        final RedisClient redis = this.client;
        if (redis == null) return false;
        final JsonObject message = new JsonObject();
        message.addProperty("account_uuid", account.toString());
        try {
            redis.publish(PERMISSION_CHANNEL, message.toString());
            up = true;
            return true;
        } catch (RuntimeException e) {
            failed(e, "publish");
            return false;
        }
    }

    /**
     * Tells every shard that the bans or the whitelist changed ({@code API-PROD-006}).
     *
     * @param kind    {@code ban}, {@code unban}, {@code whitelist-add} or {@code whitelist-remove}
     * @param subject the account or the network
     * @param banId   the ban, null for a whitelist change
     * @return false if the message could not be sent: Redis is down, or not configured
     */
    public boolean publishModeration(String kind, String subject, @Nullable Long banId) {
        final RedisClient redis = this.client;
        if (redis == null) return false;
        final JsonObject message = new JsonObject();
        message.addProperty("kind", kind);
        message.addProperty("subject", subject);
        message.addProperty("ban_id", banId);
        try {
            redis.publish(MODERATION_CHANNEL, message.toString());
            up = true;
            return true;
        } catch (RuntimeException e) {
            failed(e, "publish");
            return false;
        }
    }

    /**
     * Sets where a player is, for {@value #PRESENCE_TTL_MS} ms. Queued, see {@link #presence(Map, long)}.
     *
     * @param account   the account
     * @param sessionId the session of this shard
     * @param state     {@code loading}, {@code online} or {@code in_transit}
     */
    public void presence(UUID account, long sessionId, String state) {
        presence(Map.of(account, state), sessionId);
    }

    /**
     * Sets or refreshes where several players are, in one round trip. The write is queued for the
     * presence thread and this returns at once; past {@value #PRESENCE_QUEUE} waiting writes the
     * oldest is dropped, and the refresh every 10 s writes it again.
     *
     * @param states    the state of each account
     * @param sessionId the session of this shard
     */
    public void presence(Map<UUID, String> states, long sessionId) {
        final RedisClient redis = this.client;
        final ExecutorService writes = this.presenceWrites;
        if (redis == null || writes == null || states.isEmpty()) return;
        final Map<UUID, String> written = Map.copyOf(states);
        writes.execute(() -> {
            try (var pipeline = redis.pipelined()) {
                for (Map.Entry<UUID, String> entry : written.entrySet()) {
                    final JsonObject value = new JsonObject();
                    value.addProperty("shard_id", shardId);
                    value.addProperty("session_id", sessionId);
                    value.addProperty("state", entry.getValue());
                    pipeline.set(presenceKey(entry.getKey()), value.toString(), SetParams.setParams().px(PRESENCE_TTL_MS));
                }
                pipeline.sync();
                up = true;
            } catch (RuntimeException e) {
                failed(e, "presence");
            }
        });
    }

    /**
     * Forgets where a player is, queued behind the presence writes before it.
     *
     * @param account the account
     */
    public void clearPresence(UUID account) {
        final RedisClient redis = this.client;
        final ExecutorService writes = this.presenceWrites;
        if (redis == null || writes == null) return;
        writes.execute(() -> {
            try {
                redis.del(presenceKey(account));
                up = true;
            } catch (RuntimeException e) {
                failed(e, "presence");
            }
        });
    }

    /** A full presence queue drops its oldest write for the new one; once closed, it drops the new one. */
    private void dropOldest(Runnable write, ThreadPoolExecutor executor) {
        if (executor.isShutdown()) return;
        droppedPresence.warn("The presence queue is full at {} writes, dropping the oldest; the next refresh writes it again",
                PRESENCE_QUEUE);
        executor.getQueue().poll();
        executor.execute(write);
    }

    /**
     * Whether the last request reached Redis.
     *
     * @return true if it did
     */
    public boolean isUp() {
        return up;
    }

    static String presenceKey(UUID account) {
        return "presence:" + account;
    }

    static String kickChannel(String shardId) {
        return "synaxis:kick:" + shardId;
    }

    private void failed(RuntimeException e, String what) {
        up = false;
        failures.warn("Redis is unreachable ({}): {}", what, e.toString());
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (JedisPubSub pubSub : subscriptions) {
            try {
                pubSub.unsubscribe();
            } catch (RuntimeException _) {
                // Not subscribed, or the connection is already gone
            }
        }
        final ExecutorService writes = this.presenceWrites;
        if (writes != null) writes.shutdownNow();
        final RedisClient redis = this.client;
        if (redis != null) redis.close();
    }
}
