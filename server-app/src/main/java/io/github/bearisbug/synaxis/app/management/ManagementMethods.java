package io.github.bearisbug.synaxis.app.management;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.app.command.GmCommands;
import io.github.bearisbug.synaxis.app.command.HostCommands;
import io.github.bearisbug.synaxis.app.command.Profiler;
import io.github.bearisbug.synaxis.app.command.TickStats;
import io.github.bearisbug.synaxis.app.moderation.HostModeration;
import io.github.bearisbug.synaxis.app.shard.ShardService;
import io.github.bearisbug.synaxis.app.shard.ShardTransferResult;
import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.app.store.ShardSession;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.ConnectionGate;
import io.github.bearisbug.synaxis.plugin.Ban;
import io.github.bearisbug.synaxis.plugin.BanTarget;
import io.github.bearisbug.synaxis.plugin.LogBudget;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;

/**
 * The methods of the management interface ({@code docs/DESIGN-PROD.md} {@code API-PROD-022}):
 * the SMP compatible subset and the {@code synaxis:} extensions, each one message in and at most
 * one message out, as JSON-RPC 2.0.
 * <p>
 * {@link #handle} runs on the calling thread until the method is done, a virtual thread per call
 * in the server. Every call that reaches a method writes one {@code gm_command} event.
 */
public final class ManagementMethods {

    private static final Logger LOGGER = LoggerFactory.getLogger(ManagementMethods.class);

    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;
    static final int INTERNAL_ERROR = -32603;
    /** The call is fine, the server cannot do it right now. */
    static final int UNAVAILABLE = -32000;

    /** The reason of a ban added without one, the vanilla default. */
    static final String DEFAULT_BAN_REASON = "Banned by an operator.";
    private static final int MAX_REASON = 256;
    /** The names of a ban entry: the vanilla User Ban's, whose {@code source} is taken and not used. */
    private static final Set<String> BAN_FIELDS = Set.of("player", "reason", "expires", "source");

    private static final TypeAdapter<JsonElement> JSON = new Gson().getAdapter(JsonElement.class);

    /** What a method returned: the result, what the ledger records, and what runs once it is answered. */
    record Outcome(JsonElement result, String audit, @Nullable UUID target, @Nullable Runnable afterReply) {
        Outcome(JsonElement result, String audit, @Nullable UUID target) {
            this(result, audit, target, null);
        }
    }

    /** A call the method refuses, answered as a JSON-RPC error. */
    static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;
        final int code;
        final String audit;

        Failure(int code, String message, String audit) {
            super(message, null, false, false);
            this.code = code;
            this.audit = audit;
        }
    }

    @FunctionalInterface
    private interface Method {
        Outcome call(Call call) throws Failure;
    }

    /** One request: its method, its parameters by position or by name, and the id its log lines carry. */
    private record Call(String method, @Nullable JsonElement params, String requestId) {
        @Nullable JsonElement param(int index, String name) {
            final JsonElement value;
            if (params == null) value = null;
            else if (params.isJsonArray()) value = index < params.getAsJsonArray().size() ? params.getAsJsonArray().get(index) : null;
            else value = params.getAsJsonObject().get(name);
            return value == null || value.isJsonNull() ? null : value;
        }
    }

    /**
     * The reply to one message, and what to run once it is sent.
     *
     * @param text       the JSON text to send, null for a notification that is not answered
     * @param afterReply run after the reply is sent, null for nothing
     */
    record Reply(@Nullable String text, @Nullable Runnable afterReply) {
    }

    private final ServerProcess process;
    private final HostCommands commands;
    private final PlayerStore store;
    private final HostModeration moderation;
    private final @Nullable ShardSession session;
    private final @Nullable ShardService shards;
    private final String shardId;
    private final BiConsumer<String, Map<String, ?>> analytics;
    private final Map<String, Method> methods = new LinkedHashMap<>();
    private final LogBudget failures = new LogBudget(LOGGER, "management calls failed");
    /** Set once, by {@link #serverStarted()}; until then {@code minecraft:server/stop} is refused. */
    private volatile boolean serverStarted;

    /**
     * @param process    the engine process
     * @param commands   the built in commands, whose stop, ticks and recording the methods share
     * @param store      the player store, which {@code minecraft:server/save} saves
     * @param moderation the bans
     * @param session    the shard session, null in a process without a database
     * @param shards     what sends a player to another shard, null outside the directory mode
     * @param shardId    the shard id
     * @param analytics  the event ledger
     */
    public ManagementMethods(ServerProcess process, HostCommands commands, PlayerStore store, HostModeration moderation,
                             @Nullable ShardSession session, @Nullable ShardService shards, String shardId,
                             BiConsumer<String, Map<String, ?>> analytics) {
        this.process = process;
        this.commands = commands;
        this.store = store;
        this.moderation = moderation;
        this.session = session;
        this.shards = shards;
        this.shardId = shardId;
        this.analytics = analytics;
        methods.put("minecraft:players", _ -> new Outcome(players(process.connection().getOnlinePlayers()), "ok", null));
        methods.put("minecraft:players/kick", this::kick);
        methods.put("minecraft:server/status", _ -> status());
        methods.put("minecraft:server/save", _ -> save());
        methods.put("minecraft:server/stop", _ -> stop());
        methods.put("minecraft:bans", _ -> new Outcome(banList(), "ok", null));
        methods.put("minecraft:bans/add", this::addBans);
        methods.put("minecraft:bans/remove", this::removeBans);
        methods.put("synaxis:shard/drain", _ -> drain());
        methods.put("synaxis:shard/status", _ -> shardStatus());
        methods.put("synaxis:broadcast", this::broadcast);
        methods.put("synaxis:profile", this::profile);
        methods.put("synaxis:transfer", this::transfer);
    }

    /**
     * Lets {@code minecraft:server/stop} through to the engine, called by Main once the game port is
     * bound and the tick runs ({@code docs/DESIGN-PROD.md} §11.4). The interface listens before that,
     * while the plugins are enabled: a shutdown begun then would run before Main has registered its
     * shutdown tasks, and Main would go on to open the game port of a process that no longer ticks
     * and never exits.
     */
    public void serverStarted() {
        serverStarted = true;
    }

    /**
     * Runs one message of a management connection.
     *
     * @param message the text frame
     * @return the reply
     */
    Reply handle(String message) {
        final String requestId = UUID.randomUUID().toString();
        final JsonElement parsed;
        try {
            parsed = parse(message);
        } catch (Failure failure) {
            return error(JsonNull.INSTANCE, failure.code, failure.getMessage(), requestId);
        }
        if (!parsed.isJsonObject()) return error(JsonNull.INSTANCE, INVALID_REQUEST,
                parsed.isJsonArray() ? "Batch requests are not supported" : "A request is a JSON object", requestId);
        final JsonObject request = parsed.getAsJsonObject();
        final JsonElement id = request.get("id");
        if (id != null && !id.isJsonNull() && !(id.isJsonPrimitive() && !id.getAsJsonPrimitive().isBoolean()))
            return error(JsonNull.INSTANCE, INVALID_REQUEST, "The id is a string, a number or null", requestId);
        final JsonElement replyId = id == null ? JsonNull.INSTANCE : id;
        final JsonElement version = request.get("jsonrpc");
        if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isString()
                || !"2.0".equals(version.getAsString()))
            return error(replyId, INVALID_REQUEST, "jsonrpc has to be \"2.0\"", requestId);
        final JsonElement name = request.get("method");
        if (name == null || !name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString())
            return error(replyId, INVALID_REQUEST, "The method is a string", requestId);
        final JsonElement params = request.get("params");
        if (params != null && !params.isJsonArray() && !params.isJsonObject())
            return error(replyId, INVALID_REQUEST, "The params are an array or an object", requestId);

        // A request without an id is a notification: it runs, and nothing is sent back
        final boolean answered = id != null;
        final Method method = methods.get(name.getAsString());
        if (method == null)
            return answered ? error(replyId, METHOD_NOT_FOUND, "Method not found", requestId) : new Reply(null, null);
        final Call call = new Call(name.getAsString(), params, requestId);
        try {
            final Outcome outcome = method.call(call);
            record(call, outcome.target(), outcome.audit());
            final JsonObject response = envelope(replyId);
            response.add("result", outcome.result());
            return new Reply(answered ? response.toString() : null, outcome.afterReply());
        } catch (Failure failure) {
            record(call, null, failure.audit);
            return answered ? error(replyId, failure.code, failure.getMessage(), requestId) : new Reply(null, null);
        } catch (RuntimeException e) {
            // What was not foreseen stays on this side: the stack and the class name go into the log only
            failures.warn(e, "Management call failed: method={} request_id={}", call.method(), requestId);
            record(call, null, "unavailable");
            return answered ? error(replyId, INTERNAL_ERROR, "Internal error", requestId) : new Reply(null, null);
        }
    }

    /**
     * Reads one message as RFC 8259 JSON: one value in Gson's {@link Strictness#STRICT}, then
     * nothing but blanks. {@link com.google.gson.JsonParser#parseString} is lenient: it would run
     * a request written with single quotes, unquoted names, comments or {@code =>} and {@code ;}
     * separators. A number of 1,024 characters or more fails too, valid as it is: Gson's reader
     * holds a number in its buffer of 1,024 characters and gives up on a longer one
     * ({@code docs/DESIGN-PROD.md} §28.2).
     *
     * @param message the text frame
     * @return the value
     * @throws Failure {@value #PARSE_ERROR} when it is not JSON, {@value #INVALID_REQUEST} when one
     *                 object gives a name twice, at any depth: Gson would keep the last one, while
     *                 an audit, a proxy or a client library in front may have read the first
     */
    private static JsonElement parse(String message) throws Failure {
        final UniqueNamesReader reader = new UniqueNamesReader(message);
        try {
            final JsonElement value = JSON.read(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new Failure(PARSE_ERROR, "Parse error", "invalid");
            return value;
        } catch (NameGivenTwice twice) {
            throw new Failure(INVALID_REQUEST, "The name " + twice.getMessage() + " is given twice in one object", "invalid");
        } catch (IOException _) {
            throw new Failure(PARSE_ERROR, "Parse error", "invalid");
        }
    }

    /** A name an object of the message gives a second time. */
    private static final class NameGivenTwice extends IOException {
        private static final long serialVersionUID = 1L;

        NameGivenTwice(String name) {
            super(name, null);
        }
    }

    /** A strict reader that fails on the second occurrence of a name in one object. */
    private static final class UniqueNamesReader extends JsonReader {
        private final Deque<Set<String>> objects = new ArrayDeque<>();

        UniqueNamesReader(String message) {
            super(new StringReader(message));
            setStrictness(Strictness.STRICT);
        }

        @Override
        public void beginObject() throws IOException {
            super.beginObject();
            objects.push(new HashSet<>());
        }

        @Override
        public void endObject() throws IOException {
            super.endObject();
            objects.pop();
        }

        @Override
        public String nextName() throws IOException {
            final String name = super.nextName();
            if (!objects.element().add(name)) throw new NameGivenTwice(name);
            return name;
        }
    }

    /**
     * A notification the server sends, {@code minecraft:notification/players/joined} and the like.
     *
     * @param method the notification
     * @param player the player it is about
     * @return the JSON text
     */
    static String playerNotification(String method, Player player) {
        final JsonObject notification = new JsonObject();
        notification.addProperty("jsonrpc", "2.0");
        notification.addProperty("method", method);
        final JsonArray params = new JsonArray();
        params.add(player(player));
        notification.add("params", params);
        return notification.toString();
    }

    private static JsonObject envelope(JsonElement id) {
        final JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        return response;
    }

    private static Reply error(JsonElement id, int code, String message, String requestId) {
        final JsonObject response = envelope(id);
        final JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        final JsonObject data = new JsonObject();
        data.addProperty("request_id", requestId);
        error.add("data", data);
        response.add("error", error);
        return new Reply(response.toString(), null);
    }

    private void record(Call call, @Nullable UUID target, String result) {
        GmCommands.record(analytics, "management", null,
                call.params() == null ? call.method() : call.method() + " " + call.params(), target, result);
    }

    // minecraft:players, minecraft:players/kick, minecraft:server/*

    private static JsonObject player(Player player) {
        final JsonObject json = new JsonObject();
        json.addProperty("id", player.getUuid().toString());
        json.addProperty("name", player.getUsername());
        return json;
    }

    private static JsonArray players(Iterable<Player> players) {
        final JsonArray array = new JsonArray();
        for (Player player : players) array.add(player(player));
        return array;
    }

    private Outcome kick(Call call) throws Failure {
        final JsonArray entries = array(call.param(0, "kick"), "kick");
        final Map<Player, Component> kicks = new LinkedHashMap<>();
        for (JsonElement element : entries) {
            final JsonObject entry = object(element, "a kick entry");
            final Player player = onlinePlayer(object(entry.get("player"), "player"));
            final JsonElement message = entry.get("message");
            final Component reason = message == null || message.isJsonNull()
                    ? Component.translatable("multiplayer.disconnect.kicked", "Kicked by an operator")
                    : message(message);
            if (player != null) kicks.put(player, reason);
        }
        for (Map.Entry<Player, Component> kick : kicks.entrySet()) kick.getKey().kick(kick.getValue());
        final UUID target = kicks.size() == 1 ? kicks.keySet().iterator().next().getUuid() : null;
        return new Outcome(players(kicks.keySet()), kicks.isEmpty() ? "no_target" : "ok", target);
    }

    private Outcome status() {
        final JsonObject status = new JsonObject();
        status.addProperty("started", process.isAlive());
        status.add("players", players(process.connection().getOnlinePlayers()));
        final JsonObject version = new JsonObject();
        version.addProperty("name", MinecraftServer.VERSION_NAME);
        version.addProperty("protocol", MinecraftServer.PROTOCOL_VERSION);
        status.add("version", version);
        return new Outcome(status, "ok", null);
    }

    private Outcome save() {
        final PlayerStore.SaveCount count = store.saveOnline().join();
        final boolean all = count.saved() == count.online();
        return new Outcome(new JsonPrimitive(all), all ? "ok" : "partial", null);
    }

    private Outcome stop() throws Failure {
        if (!serverStarted)
            throw new Failure(UNAVAILABLE, "The server is still starting; stop it once it has started", "unavailable");
        // The shutdown closes this connection on its way, after waiting for the answer to be sent
        final boolean started = commands.beginStop(Duration.ZERO);
        return new Outcome(new JsonPrimitive(started), started ? "ok" : "invalid", null, commands::stopAnswered);
    }

    // minecraft:bans, minecraft:bans/add, minecraft:bans/remove

    private JsonArray banList() {
        final JsonArray list = new JsonArray();
        for (Ban ban : moderation.activeBans()) {
            if (!(ban.target() instanceof BanTarget.ByUuid(UUID uuid))) continue;
            final JsonObject entry = new JsonObject();
            final JsonObject player = new JsonObject();
            player.addProperty("id", uuid.toString());
            entry.add("player", player);
            entry.addProperty("reason", ban.reason());
            if (ban.expiresAt() != null) entry.addProperty("expires", ban.expiresAt().toString());
            list.add(entry);
        }
        return list;
    }

    private record NewBan(UUID uuid, String reason, @Nullable Instant expires) {
    }

    private Outcome addBans(Call call) throws Failure {
        final List<NewBan> bans = new ArrayList<>();
        // Every entry is checked before the first ban is written: one bad entry bans nobody
        for (JsonElement element : array(call.param(0, "add"), "add")) {
            final JsonObject entry = object(element, "a ban");
            // A misspelt expires would otherwise ban for ever
            for (String name : entry.keySet()) {
                if (!BAN_FIELDS.contains(name))
                    throw invalid("unknown field '" + name + "' in a ban; the fields are player, reason, expires, source");
            }
            final UUID uuid = account(object(entry.get("player"), "player"));
            final String reason = optionalString(entry.get("reason"), "reason", DEFAULT_BAN_REASON);
            final int length = reason.codePointCount(0, reason.length());
            if (length == 0 || length > MAX_REASON)
                throw invalid("a ban reason has 1 to " + MAX_REASON + " characters, not " + length);
            final String expiresText = optionalString(entry.get("expires"), "expires", null);
            final Instant expires;
            try {
                expires = expiresText == null ? null : Instant.parse(expiresText);
            } catch (DateTimeParseException _) {
                throw invalid("expires " + expiresText + " is not an instant like 2026-12-01T00:00:00Z");
            }
            if (expires != null && !expires.isAfter(Instant.now())) throw invalid("expires " + expires + " is not in the future");
            if (expires != null && !expires.isBefore(HostModeration.EXPIRY_LIMIT))
                throw invalid("expires " + expires + " is not before " + HostModeration.EXPIRY_LIMIT);
            bans.add(new NewBan(uuid, reason, expires));
        }
        for (NewBan ban : bans)
            joinModeration(moderation.ban(new BanTarget.ByUuid(ban.uuid()), ban.reason(), ban.expires(), HostModeration.CONSOLE));
        return new Outcome(banList(), "ok", bans.size() == 1 ? bans.getFirst().uuid() : null);
    }

    private Outcome removeBans(Call call) throws Failure {
        final Set<UUID> accounts = new HashSet<>();
        for (JsonElement element : array(call.param(0, "remove"), "remove"))
            accounts.add(account(object(element, "a player")));
        int revoked = 0;
        for (Ban ban : moderation.activeBans()) {
            if (!(ban.target() instanceof BanTarget.ByUuid(UUID uuid)) || !accounts.contains(uuid)) continue;
            if (joinModeration(moderation.unban(ban.banId(), HostModeration.CONSOLE))) revoked++;
        }
        return new Outcome(banList(), revoked == 0 ? "no_ban" : "ok",
                accounts.size() == 1 ? accounts.iterator().next() : null);
    }

    /** A write of the moderation store, its failure being a state of the server rather than a bug. */
    private static <T> T joinModeration(CompletableFuture<T> write) throws Failure {
        try {
            return write.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof IllegalStateException state)
                throw new Failure(UNAVAILABLE, "Bans cannot be changed right now: " + state.getMessage(), "unavailable");
            throw e;
        }
    }

    /** The account a Player object names: its id, or the name of a player online on this shard. */
    private UUID account(JsonObject player) throws Failure {
        final JsonElement id = player.get("id");
        if (id != null && !id.isJsonNull()) return uuid(id);
        final Player online = onlinePlayer(player);
        if (online == null)
            throw invalid("no player online here is called " + player.get("name").getAsString() + "; name others by id");
        return online.getUuid();
    }

    // synaxis:shard/*, synaxis:broadcast, synaxis:profile, synaxis:transfer

    /**
     * Closes the login gate; synchronized so that of two drains at once, each on its own call thread, only the
     * one that closed it answers true.
     */
    private synchronized Outcome drain() {
        final ConnectionGate gate = process.connection().gate();
        final boolean closedNow = !gate.isClosed();
        gate.close();
        return new Outcome(new JsonPrimitive(closedNow), closedNow ? "ok" : "invalid", null);
    }

    private Outcome shardStatus() {
        final JsonObject status = new JsonObject();
        status.addProperty("shard_id", shardId);
        if (session == null) status.add("session", JsonNull.INSTANCE);
        else status.addProperty("session", session.state().name().toLowerCase(Locale.ROOT));
        status.addProperty("online", process.connection().getOnlinePlayerCount());
        status.addProperty("draining", process.connection().gate().isClosed());
        status.addProperty("shutdown_phase", process.shutdownPhase().name().toLowerCase(Locale.ROOT));
        final TickStats.Snapshot ticks = commands.ticks();
        status.addProperty("tps", ticks.tps());
        final JsonObject mspt = new JsonObject();
        mspt.addProperty("p50", ticks.p50());
        mspt.addProperty("p95", ticks.p95());
        mspt.addProperty("p99", ticks.p99());
        status.add("mspt", mspt);
        return new Outcome(status, "ok", null);
    }

    private Outcome broadcast(Call call) throws Failure {
        final JsonElement message = call.param(0, "message");
        if (message == null) throw invalid("message is missing");
        final Component text = message(message);
        int players = 0;
        for (Player player : process.connection().getOnlinePlayers()) {
            player.sendMessage(text);
            players++;
        }
        return new Outcome(new JsonPrimitive(players), "ok", null);
    }

    private Outcome profile(Call call) throws Failure {
        final JsonElement given = call.param(0, "seconds");
        final int seconds = given == null ? Profiler.DEFAULT_SECONDS
                : wholeNumber(given, "seconds", Profiler.MIN_SECONDS, Profiler.MAX_SECONDS);
        final Profiler.Start start;
        try {
            start = commands.profiler().start(seconds);
        } catch (IOException e) {
            throw new Failure(UNAVAILABLE, "The recording cannot start: " + e.getMessage(), "unavailable");
        }
        final Profiler.Started started = switch (start) {
            case Profiler.Busy busy -> throw new Failure(UNAVAILABLE, "A recording to " + busy.path() + " is still running",
                    "invalid");
            case Profiler.Started running -> running;
        };
        try {
            started.written().join();
        } catch (CompletionException e) {
            throw new Failure(UNAVAILABLE, "The recording failed: " + e.getCause().getMessage(), "unavailable");
        }
        final JsonObject result = new JsonObject();
        result.addProperty("path", started.path().toString());
        result.addProperty("seconds", seconds);
        return new Outcome(result, "ok", null);
    }

    /**
     * Sends a player online here to another shard ({@code docs/DESIGN-M3.md} {@code API-M3-010}): to a
     * channel of {@code region} or to the channel {@code shard}, exactly one of them. Answers once the
     * transfer is known, with the result in lower case.
     */
    private Outcome transfer(Call call) throws Failure {
        if (shards == null)
            throw new Failure(UNAVAILABLE, "This shard cannot send players to other shards: it is not in the directory mode",
                    "unavailable");
        final JsonObject named = object(call.param(0, "player"), "player");
        final JsonElement region = call.param(1, "region");
        final JsonElement shard = call.param(2, "shard");
        if ((region == null) == (shard == null)) throw invalid("give either region or shard, not both and not neither");
        final String target = region != null ? string(region, "region") : string(shard, "shard");
        final Player player = onlinePlayer(named);
        if (player == null) throw invalid("the player is not online here");
        final CompletableFuture<ShardTransferResult> transfer = region != null
                ? shards.transfer(player, target) : shards.transferTo(player, target);
        final ShardTransferResult result;
        try {
            result = transfer.join();
        } catch (CompletionException e) {
            // What the transfer threw, wrapped once by the stages of the shard service
            final Throwable cause = e.getCause() == null ? e : e.getCause();
            failures.error(cause, "Management transfer failed: player={} target={} request_id={}", player.getUsername(),
                    target, call.requestId());
            throw new Failure(INTERNAL_ERROR, "Internal error", "unavailable");
        }
        final String name = result.name().toLowerCase(Locale.ROOT);
        return new Outcome(new JsonPrimitive(name), name, player.getUuid());
    }

    // Parameters

    private static Failure invalid(String message) {
        return new Failure(INVALID_PARAMS, message, "invalid");
    }

    /**
     * A JSON number that is exactly a whole number within the bounds, read from its text: 1, 1.0 and
     * 1e0 are 1, while 0.99999999999999999999 is refused. A double cannot tell it from 1, and
     * {@link JsonElement#getAsInt} would then cut it to 0.
     */
    private static int wholeNumber(JsonElement value, String name, int min, int max) throws Failure {
        final Failure invalid = invalid(name + " is a whole number from " + min + " to " + max);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw invalid;
        final int number;
        try {
            number = value.getAsBigDecimal().intValueExact();
        } catch (NumberFormatException | ArithmeticException _) {
            // A fraction, a number past int, or one whose exponent is past Gson's limit. A number too
            // long for Gson never gets here: its reader gives up on one of 1,024 characters, see parse
            throw invalid;
        }
        if (number < min || number > max) throw invalid;
        return number;
    }

    private static JsonArray array(@Nullable JsonElement value, String name) throws Failure {
        if (value == null || !value.isJsonArray()) throw invalid(name + " is an array");
        return value.getAsJsonArray();
    }

    private static JsonObject object(@Nullable JsonElement value, String name) throws Failure {
        if (value == null || !value.isJsonObject()) throw invalid(name + " is an object");
        return value.getAsJsonObject();
    }

    private static String string(JsonElement value, String name) throws Failure {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid(name + " is a string");
        return value.getAsString();
    }

    private static @Nullable String optionalString(@Nullable JsonElement value, String name, @Nullable String absent)
            throws Failure {
        return value == null || value.isJsonNull() ? absent : string(value, name);
    }

    private static UUID uuid(JsonElement value) throws Failure {
        final String text = string(value, "id");
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException _) {
            throw invalid("id is not a UUID: " + text);
        }
    }

    /** The player a Player object names on this shard, by id before name; null if not online here. */
    private @Nullable Player onlinePlayer(JsonObject player) throws Failure {
        final JsonElement id = player.get("id");
        if (id != null && !id.isJsonNull()) return process.connection().getOnlinePlayerByUuid(uuid(id));
        final JsonElement name = player.get("name");
        if (name == null || name.isJsonNull()) throw invalid("a player has an id or a name");
        return process.connection().getOnlinePlayerByUsername(string(name, "name"));
    }

    /** A Message object: a translation with its parameters, the literal text being its fallback, or a literal. */
    private static Component message(JsonElement value) throws Failure {
        final JsonObject message = object(value, "message");
        final String literal = optionalString(message.get("literal"), "literal", null);
        final String key = optionalString(message.get("translatable"), "translatable", null);
        if (key == null) {
            if (literal == null) throw invalid("a message has a literal or a translatable");
            return Component.text(literal);
        }
        final JsonElement params = message.get("translatableParams");
        final List<Component> arguments = new ArrayList<>();
        if (params != null && !params.isJsonNull()) {
            for (JsonElement param : array(params, "translatableParams")) {
                if (!param.isJsonPrimitive()) throw invalid("a translatable parameter is a string");
                arguments.add(Component.text(param.getAsString()));
            }
        }
        return Component.translatable(key, literal, arguments);
    }
}
