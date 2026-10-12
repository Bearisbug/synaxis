package io.github.bearisbug.synaxis.app.instance;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.app.scene.SceneService;
import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.generator.Generator;
import io.github.bearisbug.synaxis.instance.light.Light;
import io.github.bearisbug.synaxis.plugin.PluginAnalytics;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.instance.ManagedInstance;
import io.github.bearisbug.synaxis.world.DimensionType;
import net.kyori.adventure.key.Key;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * What the tests of the managed instances share: the match dimension type the host registers before
 * it enables any plugin, a flat map, a ledger that keeps its lines and the sky light read straight from
 * the sections.
 */
final class InstanceFixture {

    /** The stone of the flat map fills y 0 to 9; the open air starts here. */
    static final int FLOOR = 10;
    static final Pos SPAWN = new Pos(0.5, FLOOR, 0.5);
    static final Generator FLAT = unit -> unit.modifier().fillHeight(0, FLOOR, Block.STONE);

    private InstanceFixture() {
    }

    /** What the startup sequence of the host does before the plugins are enabled (Main#registerMatchDimensionType). */
    static void registerMatchDimension(ServerProcess process) {
        final Key key = Key.key(PluginApi.MATCH_DIMENSION_TYPE);
        if (process.dimensionType().getKey(key) != null) return;
        process.dimensionType().register(key, DimensionType.builder().minY(0).height(64).logicalHeight(64).build());
    }

    /** The scene layer the registry holds back a moved player's packets on; nobody owns a scene in these tests. */
    static SceneService scenes(ServerProcess process) {
        return new SceneService(process, () -> null, (_, _) -> {
        }, System::nanoTime);
    }

    /** A generator that waits for {@code release}, at most 10 s, before laying the flat map. */
    static Generator stuckUntil(CountDownLatch release) {
        return unit -> {
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("nobody released the generator");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            FLAT.generate(unit);
        };
    }

    /**
     * The flat map, where a chunk farther than {@code radius} from the origin waits for {@code release}
     * first, at most 10 s: a map of that radius prepares at once, and a chunk off it loaded later stays
     * loading for as long as the test holds the latch.
     */
    static Generator stuckBeyond(int radius, CountDownLatch release) {
        final Generator stuck = stuckUntil(release);
        return unit -> {
            final Point start = unit.absoluteStart();
            final boolean onTheMap = Math.abs(start.chunkX()) <= radius && Math.abs(start.chunkZ()) <= radius;
            (onTheMap ? FLAT : stuck).generate(unit);
        };
    }

    /**
     * The sky light the server computed for a block, read from its section as MatchIntegrationTest does:
     * {@code Instance#getSkyLight} would compute a stale section on demand and pass on an unlit map.
     */
    static int computedSkyLight(InstanceContainer instance, int x, int y, int z) {
        final Chunk chunk = instance.getChunkAt(x, z);
        assertNotNull(chunk, "no chunk holds " + x + "," + z);
        final Light light = chunk.getSectionAt(y).skyLight();
        assertFalse(light.requiresUpdate(), "the section holding " + x + "," + y + "," + z
                + " is still waiting for its light, which would be computed only when it is sent");
        return light.getLevel(x & 15, y & 15, z & 15);
    }

    /** One ledger line. */
    record Line(String event, Map<String, Object> attributes) {

        Object get(String attribute) {
            return attributes.get(attribute);
        }
    }

    /** The ledger the registry writes to, keeping its lines in order. */
    static final class Ledger implements PluginAnalytics {

        private final List<Line> lines = new CopyOnWriteArrayList<>();

        @Override
        public void record(String event, Map<String, ?> attributes) {
            lines.add(new Line(event, new LinkedHashMap<>(attributes)));
        }

        List<Line> lines() {
            return List.copyOf(lines);
        }

        /** The lines about one instance, the moves into it included. */
        List<Line> of(ManagedInstance managed) {
            final String id = managed.instanceId().toString();
            return lines.stream().filter(line -> id.equals(line.get("instance_id"))).toList();
        }

        List<String> events(ManagedInstance managed) {
            return of(managed).stream().map(Line::event).toList();
        }

        Line last(ManagedInstance managed, String event) {
            final List<Line> matching = of(managed).stream().filter(line -> line.event().equals(event)).toList();
            assertFalse(matching.isEmpty(), "no " + event + " line for " + managed.id() + " in " + lines);
            return matching.getLast();
        }
    }
}
