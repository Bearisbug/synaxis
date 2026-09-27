package io.github.bearisbug.synaxis.app.shard;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.synx.SynxManifest;

import java.util.List;

/**
 * The example shard directory of {@code docs/DESIGN-M3.md} {@code API-M3-010}, built with the
 * constructor: r0 and r1 side by side on the 4k test map, one gate each way, one channel each.
 */
final class ExampleDirectory {

    static final ShardDirectory.Gate R0_EAST = new ShardDirectory.Gate("r0-east",
            new ShardDirectory.Frame(2032, 0, 1016, 2047, 255, 1031), "r1", new Pos(2024.5, 200.0, 1024.5, 90f, 0f));
    static final ShardDirectory.Gate R1_WEST = new ShardDirectory.Gate("r1-west",
            new ShardDirectory.Frame(2048, 0, 1016, 2063, 255, 1031), "r0", new Pos(2071.5, 200.0, 1024.5, -90f, 0f));
    static final ShardDirectory.Region R0 = new ShardDirectory.Region("r0", new SynxManifest.Bounds(0, 0, 127, 127),
            List.of(R0_EAST));
    static final ShardDirectory.Region R1 = new ShardDirectory.Region("r1", new SynxManifest.Bounds(128, 0, 255, 127),
            List.of(R1_WEST));
    static final List<ShardDirectory.Channel> CHANNELS = List.of(
            new ShardDirectory.Channel("r0-c1", "r0", 1, "127.0.0.1", 25601, 200),
            new ShardDirectory.Channel("r1-c1", "r1", 1, "127.0.0.1", 25602, 200));

    private ExampleDirectory() {
    }

    /**
     * The example as one of its shards reads it.
     *
     * @param shardId {@code r0-c1} or {@code r1-c1}
     * @return the directory
     */
    static ShardDirectory as(String shardId) {
        return new ShardDirectory("synth", List.of(R0, R1), CHANNELS, shardId);
    }
}
