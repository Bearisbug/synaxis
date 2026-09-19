package io.github.bearisbug.synaxis.game.rts;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.game.protocol.GameMessage;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.TestConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One running match with one admitted player and three units on the board: one of the player's
 * team, one neutral and one of the other team — the seed {@code docs/TEST.md} §3 asks for, small
 * enough to reason about.
 * <p>
 * The clock is handed in rather than read from the system: every budget of §15 is "per second", and
 * a test that waited seconds out would spend them on every build. Ticks are driven by hand for the
 * same reason — nothing here sleeps, and nothing here waits for a real tick thread.
 */
final class SliceFixture {

    private static final Logger LOGGER = LoggerFactory.getLogger(SliceFixture.class);

    /** The team a connection is admitted with, and the team of the unit it commands. */
    static final int OURS = Roster.PLAYER_TEAM;

    /** The three units, in the order they are spawned, which is the order they are numbered in. */
    static final int MINE = 1;
    static final int NEUTRAL = 2;
    static final int THEIRS = 3;

    private final AtomicLong nanos = new AtomicLong();

    final Match match;
    final MapPreset preset;
    final NavGrid grid;
    final InstanceContainer instance;
    final GameSessions sessions;
    final Units units;
    final GameChannel channel;
    final RecordedChannels channels = new RecordedChannels();
    final RecordedEvents events = new RecordedEvents();
    final TestConnection connection;
    final Player player;

    private SliceFixture(Env env) {
        this.match = new Match(env.process(), MatchSupport.registerMatchDimension(env), events, LOGGER);
        match.load(MapPreset.SMALL.id());
        this.preset = Objects.requireNonNull(match.preset(), "the match did not come up");
        this.grid = Objects.requireNonNull(match.navGrid());
        this.instance = Objects.requireNonNull(match.instance());
        this.sessions = new GameSessions(env.process().connection());
        this.units = new Units(match, instance, grid, preset, sessions, channels, events, nanos::get);
        this.channel = new GameChannel(match, sessions, units, channels, events, nanos::get);

        this.connection = env.createConnection();
        this.player = connection.connect(instance, match.spawn());
        // Admitted before a unit is announced to it, the way the startup sequence has it: the
        // admission decides the team on the configuration thread, well before the player is put
        // into the instance and starts viewing anything
        sessions.admitted(connectionId(), OURS);

        units.spawn(Archetype.SOLDIER, OURS, match.spawn());
        units.spawn(Archetype.SOLDIER, 0, standNear(8, 0));
        units.spawn(Archetype.SOLDIER, 2, standNear(0, 8));
    }

    static SliceFixture start(Env env) {
        return new SliceFixture(env);
    }

    UUID connectionId() {
        return player.getPlayerConnection().connectionId();
    }

    /** Hands one message to the channel, the way the host router would. */
    void receive(GameMessage message) {
        final MsgBuf buf = MsgBuf.writing();
        message.write(buf);
        channel.receive(player, buf.toByteArray());
    }

    void receive(byte... payload) {
        channel.receive(player, payload);
    }

    /** Runs the unit task, which is what answers a queued order and moves what is moving. */
    void tick(int ticks) {
        for (int i = 0; i < ticks; i++) units.tick();
    }

    /** Moves the clock into the next whole second, so the per second budgets refill. */
    void advanceOneSecond() {
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(1));
    }

    /** A walkable column near the spawn, offset by the given number of blocks. */
    Pos standNear(int offsetX, int offsetZ) {
        final Pos spawn = match.spawn();
        final int x = (int) Math.floor(spawn.x()) + offsetX;
        final int z = (int) Math.floor(spawn.z()) + offsetZ;
        for (int ring = 0; ring < preset.sizeX(); ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    if (grid.isWalkable(x + dx, z + dz))
                        return new Pos(x + dx + 0.5, grid.surfaceY(x + dx, z + dz), z + dz + 0.5);
                }
            }
        }
        throw new IllegalStateException("no walkable column near " + x + "," + z);
    }
}
