package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.JvmFlags;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerPreLoginEvent;
import io.github.bearisbug.synaxis.event.player.PlayerDisconnectEvent;
import io.github.bearisbug.synaxis.event.server.ServerTickMonitorEvent;
import io.github.bearisbug.synaxis.network.DisconnectReasons;
import io.github.bearisbug.synaxis.network.packet.server.common.PluginMessagePacket;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.network.plugin.LoginPlugin;
import io.github.bearisbug.synaxis.plugin.DisconnectKeys;
import io.github.bearisbug.synaxis.plugin.LogBudget;
import io.github.bearisbug.synaxis.protocol.C2SHello;
import io.github.bearisbug.synaxis.protocol.HandshakeMessage;
import io.github.bearisbug.synaxis.protocol.HandshakeMessages;
import io.github.bearisbug.synaxis.protocol.MalformedMessageException;
import io.github.bearisbug.synaxis.protocol.MsgBuf;
import io.github.bearisbug.synaxis.protocol.ProtocolSchema;
import io.github.bearisbug.synaxis.protocol.S2CHello;
import io.github.bearisbug.synaxis.protocol.UnknownMessageException;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The client mod handshake of {@code API-M2-002} and the hand off it needs (REQ-M2-008, and the
 * second half of REQ-M2-024).
 * <p>
 * <b>Two threads share one decision.</b> {@code C2S_HELLO} arrives as a custom payload, which the
 * engine interprets and dispatches from inside a tick; the connection is let into an instance by
 * setting the spawning instance, which is only meaningful on the configuration virtual thread that
 * event runs on. A {@link CompletableFuture} per connection is the whole mechanism, and it is also
 * the only arbiter: the tick thread judges a hello only if its {@code complete} wins, the waiting
 * thread kicks on the budget only if its {@code completeExceptionally} wins, so a hello and a
 * timeout that happen at the same moment can never both take effect ({@code docs/DESIGN-M2.md}
 * §16). The answer to a hello is the waiting thread's to send, not the tick thread's: it is the
 * thread that goes on to queue {@code FinishConfigurationPacket}, and only an answer queued before
 * that reaches the mod in the one phase it reads it in.
 * <p>
 * <b>Which phase the handshake runs in is a launch argument</b>, {@code configuration} by default.
 * {@code ADR-M2-010} chose the configuration phase, but that decision rests on an unproven
 * client side fact — whether a Fabric client can send a custom payload in that phase to a server
 * that never sends {@code minecraft:register} — and the mod that would prove it does not exist yet.
 * The login phase does the same negotiation over a login plugin request, where a client that does
 * not know the channel answers with an empty payload instead of staying silent. Both are covered by
 * tests; once the spike answers, the losing path goes away (§28).
 * <p>
 * <b>Requiring the handshake is the gameplay mode's business.</b> The mod is what renders the game,
 * so a process serving no game has nothing to require it for: in the core mode the gate still
 * answers a hello and still refuses one that names another schema, but it never waits for one and
 * never kicks over its absence — otherwise excluding the gameplay plugin, which is how the core is
 * load tested and smoke tested, would also lock every tool out of the port
 * ({@code API-M2-010} step 5). With nobody waiting, the tick thread answers there, and that answer
 * can land after {@code FinishConfigurationPacket}, where the mod drops it (§16).
 */
final class HandshakeGate {

    private static final Logger LOGGER = LoggerFactory.getLogger(HandshakeGate.class);

    /**
     * The host reserved channel this negotiation runs on ({@code API-M2-001}).
     */
    static final String CHANNEL = "synaxis:handshake";

    /**
     * Which phase the handshake runs in, {@code configuration} when unset.
     */
    static final String PHASE_PROPERTY = "synaxis.app.handshake-phase";

    /**
     * The bounded wait of §17, task {@code handshake.await}. It covers waiting for
     * {@code C2S_HELLO} and nothing else — the admission call that follows is inline on the same
     * thread and has its own soft budget.
     */
    static final Duration BUDGET = Duration.ofSeconds(3);

    /**
     * What a connection that never handshook carries into its admission request, which only the
     * core mode produces.
     */
    static final HelloResult NONE = new HelloResult(0, 0);

    /**
     * Where the handshake happens. The configuration phase is {@code ADR-M2-010}; the login phase
     * is the fallback that decision names, kept working so that the answer to spike 4 costs a
     * launch argument rather than a rewrite.
     */
    enum Phase {
        /** Wait for a custom payload after the client has logged in. */
        CONFIGURATION,
        /** Ask for one with a login plugin request, before the client is a player. */
        LOGIN
    }

    /**
     * What a client told us about itself.
     *
     * @param modVersion    the client mod build; it never takes part in a decision, it only gets
     *                      handed to the admission provider and written to the ledger
     * @param schemaVersion the wire schema it speaks, which must equal ours exactly
     */
    record HelloResult(int modVersion, int schemaVersion) {
    }

    /**
     * What is held for one connection: the future both threads decide through, and the connection
     * that future belongs to. The connection is what makes the entry reclaimable — a future says
     * nothing about whether anybody is still there to hear the answer.
     */
    private record Session(PlayerConnection connection, CompletableFuture<HelloResult> hello) {
    }

    private final Phase phase;
    private final Duration budget;
    private final EventLedger ledger;

    /**
     * One entry per live connection, created by whichever side gets there first: the hello may be
     * interpreted before the configuration thread starts waiting for it, and the wait may start
     * before the hello arrives. Entries go away with the connection, which is why each one carries
     * the connection it belongs to.
     */
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    /**
     * The tick this process is on, which {@code S2C_HELLO} carries so that a client can line its
     * interpolation up from the first snapshot. The engine keeps no process wide tick number, and
     * counting the one event it fires per tick is cheaper than asking it for one.
     */
    private final AtomicLong ticks = new AtomicLong();

    /**
     * The budget of every line written here. One for all of them on purpose: a client picks the
     * channel, the payload and the number of payloads, so per reason budgets would be per payload
     * budgets ({@code docs/DESIGN.md} §15).
     */
    private final LogBudget refusals = new LogBudget(LOGGER, "refused handshake payloads");

    private volatile boolean required;

    HandshakeGate(Phase phase, Duration budget, EventLedger ledger) {
        this.phase = phase;
        this.budget = budget;
        this.ledger = ledger;
    }

    /**
     * @return the requested phase, {@link Phase#CONFIGURATION} when the property is not set
     * @throws IllegalArgumentException if the property is set to something that is not a phase
     */
    static Phase phase() {
        final String property = System.getProperty(PHASE_PROPERTY);
        if (property == null) return Phase.CONFIGURATION;
        final String value = property.strip().toUpperCase(Locale.ROOT);
        for (Phase phase : Phase.values())
            if (phase.name().equals(value)) return phase;
        throw new IllegalArgumentException(PHASE_PROPERTY + "=" + property
                + " is not one of configuration, login");
    }

    /**
     * Attaches the listeners this gate needs. The inbound payload does not arrive through one of
     * them — it comes from the channel router, which owns the single payload listener of the host.
     *
     * @param node the host node
     */
    void attach(EventNode<Event> node) {
        node.addListener(ServerTickMonitorEvent.class, _ -> {
            ticks.incrementAndGet();
            reclaimClosedSessions();
        });
        node.addListener(PlayerDisconnectEvent.class,
                event -> abandon(event.getPlayer().getPlayerConnection().connectionId()));
        if (phase == Phase.LOGIN) node.addListener(AsyncPlayerPreLoginEvent.class, this::negotiateDuringLogin);
    }

    /**
     * Makes the handshake mandatory, which the startup sequence does for a gameplay launch only.
     */
    void require() {
        this.required = true;
    }

    /**
     * Counts what this gate is holding, which is the invariant its reclamation has to keep.
     *
     * @return how many connections it still holds a decision for
     */
    int openSessions() {
        return sessions.size();
    }

    /**
     * Handles one payload on {@value #CHANNEL}, on the tick thread.
     *
     * @param player  the sender
     * @param payload the payload bytes
     */
    void receive(Player player, byte[] payload) {
        final PlayerConnection connection = player.getPlayerConnection();
        final UUID connectionId = connection.connectionId();
        if (!connection.isOnline()) return;
        final C2SHello hello = decode(connection, connectionId, payload);
        if (hello == null) return;
        final CompletableFuture<HelloResult> session = session(connection);
        if (!session.complete(new HelloResult(hello.modVersion(), hello.schemaVersion()))) {
            // Lost to the other thread. Either the budget was spent and this connection is already
            // being kicked, or this is a second hello on a connection that already had its one.
            if (!session.isCompletedExceptionally())
                refuse(connection, connectionId, "a connection sends one C2S_HELLO and this is the second");
            return;
        }
        if (hello.schemaVersion() != ProtocolSchema.SCHEMA_VERSION) {
            // Kicking belongs to the thread that then returns from the configuration event
            // without a spawning instance, because the engine demands one two statements later:
            // from here it races that check and the player gets synaxis:internal-error instead.
            // Only the core mode has no such thread, and there this one is it.
            if (!required) reject(connection, connectionId, DisconnectKeys.PROTOCOL_VERSION_MISMATCH,
                    "version-mismatch", hello.schemaVersion());
            return;
        }
        // The waiting thread answers. Answering from here would race the thread complete just woke,
        // which can queue FinishConfigurationPacket first and turn this answer into a play packet.
        if (required) return;
        player.sendPluginMessage(CHANNEL, answer());
        verified(connectionId, hello.modVersion(), hello.schemaVersion());
    }

    /**
     * Waits for the handshake of one connection, on its configuration virtual thread.
     *
     * @param player the connecting player
     * @return what the client reported, or {@code null} when this connection is not going anywhere
     *         — it was kicked here or it is already gone, and the caller must return without
     *         setting a spawning instance
     */
    @Nullable HelloResult await(Player player) {
        final PlayerConnection connection = player.getPlayerConnection();
        final UUID connectionId = connection.connectionId();
        ledger.record("session_entered_configuration", attributes(connectionId,
                "protocol_version", connection.getProtocolVersion()));
        return decide(connection, connectionId);
    }

    private @Nullable HelloResult decide(PlayerConnection connection, UUID connectionId) {
        final CompletableFuture<HelloResult> session = session(connection);
        if (!required) {
            // The core mode lets a connection in whether or not it handshook, so there is nothing
            // to wait for: a hello that already arrived was answered and judged by the tick thread,
            // and one that has not may still arrive later, on that same thread. All this reads is
            // what to put in the admission request, and whether the judgement left anybody to admit.
            if (!connection.isOnline()) return null;
            return session.isDone() && !session.isCompletedExceptionally() ? session.join() : NONE;
        }
        // It may have left between the two, and the disconnect callback had nothing to wake yet:
        // the flag is cleared before that callback runs, so re-reading it here closes the window.
        if (!connection.isOnline()) return null;
        HelloResult result;
        try {
            result = session.get(budget.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException _) {
            // Claim the future before kicking: a hello landing in this very moment must find the
            // session decided and drop, instead of answering a connection that is being closed.
            if (session.completeExceptionally(new TimeoutException("no C2S_HELLO within " + budget))) {
                reject(connection, connectionId, DisconnectKeys.CLIENT_MOD_REQUIRED, "mod-missing", 0);
                return null;
            }
            // Lost the claim. To the disconnect callback, and nobody is left to answer; or to a hello
            // that landed in this very moment, and it counts: its tick thread left the answer to us.
            if (session.isCompletedExceptionally()) return null;
            result = session.join();
        } catch (ExecutionException _) {
            // The connection went away while we waited; the disconnect callback woke us on purpose.
            return null;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (result.schemaVersion() != ProtocolSchema.SCHEMA_VERSION) {
            reject(connection, connectionId, DisconnectKeys.PROTOCOL_VERSION_MISMATCH,
                    "version-mismatch", result.schemaVersion());
            return null;
        }
        // Queued by the thread that later queues FinishConfigurationPacket, so it is written first:
        // the write thread moves the connection to the play state at that packet, and the mod reads
        // this channel in the configuration phase only. The login phase asked with S2C_HELLO already.
        if (phase == Phase.CONFIGURATION) {
            connection.sendPacket(new PluginMessagePacket(CHANNEL, answer()));
            verified(connectionId, result.modVersion(), result.schemaVersion());
        }
        return result;
    }

    /**
     * The login phase form of the same negotiation: the server asks and the client answers, so a
     * client that does not know the channel reports itself with an empty payload rather than with
     * silence. Runs on the login virtual thread of that connection, before it is a player.
     */
    private void negotiateDuringLogin(AsyncPlayerPreLoginEvent event) {
        // The ban check runs first on the same node and may have turned the connection away
        if (!required || !event.getConnection().isOnline()) return;
        final PlayerConnection connection = event.getConnection();
        final UUID connectionId = connection.connectionId();
        final CompletableFuture<LoginPlugin.Response> answered;
        try {
            answered = event.sendPluginRequest(CHANNEL, answer());
        } catch (IllegalStateException _) {
            // Disconnected after the check above: login plugin requests end with the connection.
            return;
        }
        final byte[] payload;
        try {
            payload = answered.get(budget.toMillis(), TimeUnit.MILLISECONDS).payload();
        } catch (TimeoutException | ExecutionException _) {
            reject(connection, connectionId, DisconnectKeys.CLIENT_MOD_REQUIRED, "mod-missing", 0);
            return;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return;
        }
        if (payload == null) {
            // The vanilla answer to a channel the client does not implement.
            reject(connection, connectionId, DisconnectKeys.CLIENT_MOD_REQUIRED, "mod-missing", 0);
            return;
        }
        final C2SHello hello = decode(connection, connectionId, payload);
        if (hello == null) return;
        if (hello.schemaVersion() != ProtocolSchema.SCHEMA_VERSION) {
            reject(connection, connectionId, DisconnectKeys.PROTOCOL_VERSION_MISMATCH,
                    "version-mismatch", hello.schemaVersion());
            return;
        }
        session(connection).complete(new HelloResult(hello.modVersion(), hello.schemaVersion()));
        verified(connectionId, hello.modVersion(), hello.schemaVersion());
    }

    /**
     * @return the decoded hello, or {@code null} when the payload was dropped or punished
     */
    private @Nullable C2SHello decode(PlayerConnection connection, UUID connectionId, byte[] payload) {
        final HandshakeMessage message;
        try {
            message = HandshakeMessages.read(MsgBuf.reading(payload));
        } catch (UnknownMessageException e) {
            // §9: an id this channel does not define is a fact about the sender, not an offence.
            refusals.warn("Dropped an unknown handshake message: connection_id={} msg_id=0x{}",
                    connectionId, Integer.toHexString(e.msgId()));
            return null;
        } catch (MalformedMessageException e) {
            refuse(connection, connectionId, "the handshake payload does not decode: " + e.getMessage());
            return null;
        }
        if (message instanceof C2SHello hello) return hello;
        refuse(connection, connectionId, "S2C_HELLO is an answer of ours, not something a client sends");
        return null;
    }

    private void refuse(PlayerConnection connection, UUID connectionId, String cause) {
        refusals.warn("Refusing a handshake: connection_id={} reason={} cause={}",
                connectionId, "synaxis:protocol-error", cause);
        connection.kick(DisconnectReasons.PROTOCOL_ERROR);
    }

    private void reject(PlayerConnection connection, UUID connectionId, String reasonKey,
                        String ledgerReason, int schemaVersion) {
        ledger.record("client_rejected", attributes(connectionId,
                "reason", ledgerReason, "schema_version", schemaVersion));
        // The engine writes one INFO per kick carrying the connection id and this key, which is the
        // line §14 asks for; kicking and returning is the whole action of the failing branches.
        connection.kick(Component.translatable(reasonKey));
    }

    private void verified(UUID connectionId, int modVersion, int schemaVersion) {
        ledger.record("client_verified", attributes(connectionId,
                "mod_version", modVersion, "schema_version", schemaVersion));
    }

    private CompletableFuture<HelloResult> session(PlayerConnection connection) {
        return sessions.computeIfAbsent(connection.connectionId(),
                _ -> new Session(connection, new CompletableFuture<>())).hello();
    }

    private void abandon(UUID connectionId) {
        final Session session = sessions.remove(connectionId);
        if (session != null) session.hello().completeExceptionally(new ClosedSessionException(connectionId));
    }

    /**
     * Gives back what is held for connections that are gone. {@link PlayerDisconnectEvent} only
     * fires for a connection that got as far as being a player, while the login phase form of the
     * handshake holds an entry before that — and a client may open such a connection and drop it
     * as often as it likes — so the reclamation that covers every entry is this one: a walk, once
     * per tick, of a map as long as the connection count.
     */
    private void reclaimClosedSessions() {
        for (Session session : sessions.values())
            if (!session.connection().isOnline()) abandon(session.connection().connectionId());
    }

    private byte[] answer() {
        return new S2CHello(ProtocolSchema.SCHEMA_VERSION, JvmFlags.SERVER_TICKS_PER_SECOND,
                (int) (ticks.get() % Integer.MAX_VALUE)).encode();
    }

    private static Map<String, Object> attributes(UUID connectionId, Object... pairs) {
        final Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("connection_id", connectionId);
        for (int i = 0; i < pairs.length; i += 2) attributes.put((String) pairs[i], pairs[i + 1]);
        return attributes;
    }

    /**
     * Why a bounded wait ended early: the connection is gone, so there is nothing left to decide.
     */
    @SuppressWarnings("serial") // never serialized
    private static final class ClosedSessionException extends RuntimeException {

        ClosedSessionException(UUID connectionId) {
            super("connection_id=" + connectionId + " closed before it handshook");
        }
    }
}
