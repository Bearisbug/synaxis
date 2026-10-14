package io.github.bearisbug.synaxis.app.shard;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shard directory {@code shards.json} ({@code docs/DESIGN-M3.md} {@code API-M3-010}): the example
 * of the design reads into its records, every rule of the format refuses the start naming the array
 * index or id at fault (§19 「分片目录写错时拒启」), and the three checks against the map release.
 */
public class ShardDirectoryTest {

    @TempDir
    Path directory;

    /** A resource under {@code shards/}, the example of the design and its bad versions. */
    static Path resource(String name) {
        final URL url = Objects.requireNonNull(ShardDirectoryTest.class.getResource("/shards/" + name), name);
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String example() throws IOException {
        return Files.readString(resource(ShardDirectory.FILE_NAME));
    }

    private static String prefix(Path file) {
        return "the shard directory " + file.toAbsolutePath() + " is not valid: ";
    }

    /** Loads a bad version of the example, which must be refused with the prefix and name the detail. */
    private static String refused(Path file) {
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> ShardDirectory.load(file, "r0-c1"));
        assertTrue(refused.getMessage().startsWith(prefix(file)), refused.getMessage());
        return refused.getMessage().substring(prefix(file).length());
    }

    /** The example with one piece of text replaced, which has to be there. */
    private String refused(String from, String to) throws IOException {
        final String example = example();
        assertTrue(example.contains(from), from);
        final Path file = directory.resolve(ShardDirectory.FILE_NAME);
        Files.writeString(file, example.replace(from, to));
        return refused(file);
    }

    /** The manifest of the 4k test map {@code synth} the example belongs to: 256 × 256 chunks, y 0 to 255. */
    static SynxManifest synth(String mapId, SynxManifest.Bounds bounds, SynxManifest.World world) {
        return new SynxManifest(mapId, mapId + "-000000000000-p776", new byte[32], "26.2", 776, new byte[32], 1, 1,
                world, bounds, List.of(), List.of(), new SynxManifest.Source("generator", null, null),
                new SynxManifest.Bake("2026-09-28T00:00:00Z", 0, 0, 1, "25"), new SynxManifest.Verify(0, 0, null));
    }

    private static SynxManifest synth() {
        return synth("synth", new SynxManifest.Bounds(0, 0, 255, 255), new SynxManifest.World(0, 256));
    }

    private static String checkRefused(ShardDirectory shards, SynxManifest manifest) {
        return assertThrows(IllegalArgumentException.class, () -> shards.checkAgainst(manifest)).getMessage();
    }

    @Test
    public void theExampleOfTheDesignReadsIntoItsRecords() {
        final ShardDirectory shards = ShardDirectory.load(resource(ShardDirectory.FILE_NAME), "r0-c1");

        assertEquals("synth", shards.mapId());
        assertEquals("r0-c1", shards.shardId());
        assertEquals(List.of(
                new ShardDirectory.Region("r0", new SynxManifest.Bounds(0, 0, 127, 127), List.of(new ShardDirectory.Gate(
                        "r0-east", new ShardDirectory.Frame(2032, 0, 1016, 2047, 255, 1031), "r1",
                        new Pos(2024.5, 200.0, 1024.5, 90f, 0f)))),
                new ShardDirectory.Region("r1", new SynxManifest.Bounds(128, 0, 255, 127), List.of(new ShardDirectory.Gate(
                        "r1-west", new ShardDirectory.Frame(2048, 0, 1016, 2063, 255, 1031), "r0",
                        new Pos(2071.5, 200.0, 1024.5, -90f, 0f))))), shards.regions());
        assertEquals(List.of(new ShardDirectory.Channel("r0-c1", "r0", 1, "127.0.0.1", 25601, 200),
                new ShardDirectory.Channel("r1-c1", "r1", 1, "127.0.0.1", 25602, 200)), shards.channels());
        assertEquals("r0", shards.ownRegion().regionId());
        assertEquals(200, shards.ownChannel().capacity());
        assertEquals("r0-east", Objects.requireNonNull(shards.gateTo("r1")).gateId());
        assertEquals("r1", ShardDirectory.load(resource(ShardDirectory.FILE_NAME), "r1-c1").ownRegion().regionId());
    }

    /** §19: eight of the nine bad versions, of which the last one only fails against the map release. */
    @Test
    public void theBadVersionsOfTheDesignAreRefusedNamingWhatIsWrong() {
        final String overlapping = refused(resource("overlapping-bounds.json"));
        assertTrue(overlapping.startsWith("regions[1] r1: "), overlapping);
        assertTrue(overlapping.contains("overlap") && overlapping.contains("regions[0] r0"), overlapping);

        final String itself = refused(resource("gate-to-itself.json"));
        assertEquals("regions[0] r0: gates[0] r0-east: target_region r0 is its own region", itself);

        final String arrival = refused(resource("arrival-in-gate.json"));
        assertTrue(arrival.startsWith("regions[0] r0: gates[0] r0-east: arrival "), arrival);
        assertTrue(arrival.endsWith(" is inside the gate r0-east"), arrival);

        final String twoGates = refused(resource("two-gates-one-pair.json"));
        assertTrue(twoGates.startsWith("regions[0] r0: gates[1] r0-east-north: "), twoGates);
        assertTrue(twoGates.contains("r1") && twoGates.contains("gates[0] r0-east"), twoGates);

        final String shardId = refused(resource("shard-id-r0-1.json"));
        assertTrue(shardId.startsWith("channels[0] r0-1: "), shardId);
        assertTrue(shardId.contains("r0-c1"), shardId);

        final String capacity = refused(resource("capacity-501.json"));
        assertEquals("channels[0] r0-c1: capacity 501 is not from 1 to 500", capacity);

        final String unknown = refused(resource("unknown-field-capcity.json"));
        assertEquals("channels[0]: unknown field capcity", unknown);

        final ShardDirectory otherMap = ShardDirectory.load(resource("other-map-id.json"), "r0-c1");
        final String mapId = checkRefused(otherMap, synth());
        assertTrue(mapId.startsWith("map_id synth2 is not synth"), mapId);
        assertTrue(mapId.contains("synth-000000000000-p776"), mapId);
    }

    @Test
    public void aShardIdThatIsNoChannelIsRefused() {
        final Path file = resource(ShardDirectory.FILE_NAME);
        for (String shardId : List.of("local", "r2-c1", "r0-c2")) {
            final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> ShardDirectory.load(file, shardId));
            assertEquals("synaxis.app.shard-id=" + shardId + " is not a channel of the shard directory "
                    + file.toAbsolutePath(), refused.getMessage());
        }
    }

    @Test
    public void aFileThatIsNotStrictJsonIsRefused() throws IOException {
        assertTrue(refused("\"format_version\": 1,", "\"format_version\": 1, // the first\n")
                .startsWith("not JSON: "));
        assertTrue(refused("\"map_id\": \"synth\"", "'map_id': 'synth'").startsWith("not JSON: "));
        final Path trailing = directory.resolve("trailing.json");
        Files.writeString(trailing, example() + "{}");
        assertTrue(refused(trailing).startsWith("not JSON: "));
        final Path array = directory.resolve("array.json");
        Files.writeString(array, "[" + example() + "]");
        assertEquals("not a JSON object", refused(array));
        final Path missing = directory.resolve("missing.json");
        final IllegalArgumentException unreadable = assertThrows(IllegalArgumentException.class,
                () -> ShardDirectory.load(missing, "r0-c1"));
        assertTrue(unreadable.getMessage().startsWith(prefix(missing) + "cannot be read: "), unreadable.getMessage());
    }

    /** Gson would keep the last one; a name given twice in one object is refused wherever it is. */
    @Test
    public void aNameGivenTwiceIsRefused() throws IOException {
        assertEquals("$.channels[0].capacity is given twice", refused("\"capacity\": 200}", "\"capacity\": 200, \"capacity\": 300}"));
        assertEquals("$.map_id is given twice", refused("\"map_id\": \"synth\",", "\"map_id\": \"synth\", \"map_id\": \"other\","));
    }

    @Test
    public void unknownMissingAndMistypedFieldsAreRefused() throws IOException {
        assertEquals("unknown field format", refused("\"format_version\": 1,", "\"format_version\": 1, \"format\": 1,"));
        assertEquals("format_version 2 is not 1", refused("\"format_version\": 1,", "\"format_version\": 2,"));
        assertEquals("format_version is missing", refused("\"format_version\": 1,", ""));
        assertEquals("regions[0] r0: bounds: unknown field max_y",
                refused("\"max_z\": 127}", "\"max_z\": 127, \"max_y\": 1}"));
        assertEquals("regions[0] r0: gates[0]: arrival is missing",
                refused(", \"arrival\": [2024.5, 200.0, 1024.5, 90.0]", ""));
        assertEquals("channels[0] r0-c1: port is not an integer: 25601.5", refused("25601", "25601.5"));
        assertEquals("channels[0] r0-c1: port is not an integer: \"25601\"", refused("25601", "\"25601\""));
        assertEquals("channels[0] r0-c1: channel is not an integer: 4294967297",
                refused("\"channel\": 1, \"host\": \"127.0.0.1\", \"port\": 25601", "\"channel\": 4294967297, \"host\": \"127.0.0.1\", \"port\": 25601"));
        assertEquals("map_id is not a string", refused("\"map_id\": \"synth\"", "\"map_id\": 7"));
        assertEquals("regions[0] r0: gates[0] r0-east: min is not 3 integers", refused("[2032, 0, 1016]", "[2032, 0]"));
        assertEquals("regions[0] r0: gates[0] r0-east: arrival is not 4 finite numbers",
                refused("[2024.5, 200.0, 1024.5, 90.0]", "[2024.5, 200.0, 1024.5, 1e400]"));
        final Path object = directory.resolve("object.json");
        Files.writeString(object, "{\"format_version\": 1, \"map_id\": \"synth\", \"regions\": {}, \"channels\": []}");
        assertEquals("regions is not an array", refused(object));
    }

    @Test
    public void idsFollowTheirPatternAndAreUnique() throws IOException {
        assertEquals("regions[0]: region_id R0 is not written [a-z0-9][a-z0-9_-]{0,31}",
                refused("\"region_id\": \"r0\", \"bounds\"", "\"region_id\": \"R0\", \"bounds\""));
        assertEquals("regions[1]: region_id r0 is given twice",
                refused("\"region_id\": \"r1\", \"bounds\"", "\"region_id\": \"r0\", \"bounds\""));
        assertEquals("regions[1] r1: gates[0]: gate_id r0-east is given twice, see regions[0] r0",
                refused("\"gate_id\": \"r1-west\"", "\"gate_id\": \"r0-east\""));
        assertEquals("regions[0] r0: gates[0]: gate_id -east is not written [a-z0-9][a-z0-9_-]{0,31}",
                refused("\"gate_id\": \"r0-east\"", "\"gate_id\": \"-east\""));
    }

    @Test
    public void boundsFramesAndArrivalsStayInTheirRegion() throws IOException {
        assertEquals("regions[0] r0: bounds 0,0,-1,127 are empty", refused("\"max_x\": 127, \"max_z\": 127}",
                "\"max_x\": -1, \"max_z\": 127}"));
        assertEquals("regions[0] r0: gates[0] r0-east: min 2032,0,1016 is past max 2031,255,1031",
                refused("\"max\": [2047, 255, 1031]", "\"max\": [2031, 255, 1031]"));
        assertEquals("regions[0] r0: gates[0] r0-east: the frame x 2032..2048, z 1016..1031 is not within the blocks "
                + "x 0..2047, z 0..2047 of the region", refused("\"max\": [2047, 255, 1031]", "\"max\": [2048, 255, 1031]"));
        assertEquals("regions[0] r0: gates[0] r0-east: the frame x 2032..2047, z -1..1031 is not within the blocks "
                + "x 0..2047, z 0..2047 of the region", refused("\"min\": [2032, 0, 1016]", "\"min\": [2032, 0, -1]"));
        assertEquals("regions[0] r0: gates[0] r0-east: the frame x 2032..2047, z 1016..2048 is not within the blocks "
                + "x 0..2047, z 0..2047 of the region", refused("\"max\": [2047, 255, 1031]", "\"max\": [2047, 255, 2048]"));
        assertEquals("regions[1] r1: gates[0] r1-west: the frame x 2047..2063, z 1016..1031 is not within the blocks "
                + "x 2048..4095, z 0..2047 of the region", refused("\"min\": [2048, 0, 1016]", "\"min\": [2047, 0, 1016]"));
        assertEquals("regions[0] r0: gates[0] r0-east: arrival 2048.0, 200.0, 1024.5 is not within the blocks "
                        + "x 0..2047, z 0..2047 of the region",
                refused("[2024.5, 200.0, 1024.5, 90.0]", "[2048.0, 200.0, 1024.5, 90.0]"));
        // An arrival is judged by the block it stands on: x 2031.99 is block 2031, next to the frame from 2032
        final Path edge = directory.resolve("edge.json");
        Files.writeString(edge, example().replace("[2024.5, 200.0, 1024.5, 90.0]", "[2031.99, 200.0, 1024.5, 90.0]"));
        assertEquals(new Pos(2031.99, 200.0, 1024.5, 90f, 0f), ShardDirectory.load(edge, "r0-c1").ownRegion().gates().getFirst().arrival());
        assertEquals("regions[0] r0: gates[0] r0-east: arrival 2032.0, 200.0, 1024.5 is inside the gate r0-east",
                refused("[2024.5, 200.0, 1024.5, 90.0]", "[2032.0, 200.0, 1024.5, 90.0]"));
    }

    /** The arrival against the blocks of its region on the edges the test above leaves out, z and the west. */
    @Test
    public void anArrivalStandsOnABlockOfItsRegion() throws IOException {
        // z 2047.99 is block 2047, the last row of the region
        final Path edge = directory.resolve("edge.json");
        Files.writeString(edge, example().replace("[2024.5, 200.0, 1024.5, 90.0]", "[2024.5, 200.0, 2047.99, 90.0]"));
        assertEquals(new Pos(2024.5, 200.0, 2047.99, 90f, 0f), ShardDirectory.load(edge, "r0-c1").ownRegion().gates().getFirst().arrival());
        assertEquals("regions[0] r0: gates[0] r0-east: arrival 2024.5, 200.0, 2048.0 is not within the blocks "
                        + "x 0..2047, z 0..2047 of the region",
                refused("[2024.5, 200.0, 1024.5, 90.0]", "[2024.5, 200.0, 2048.0, 90.0]"));
        // z -0.5 is block -1
        assertEquals("regions[0] r0: gates[0] r0-east: arrival 2024.5, 200.0, -0.5 is not within the blocks "
                        + "x 0..2047, z 0..2047 of the region",
                refused("[2024.5, 200.0, 1024.5, 90.0]", "[2024.5, 200.0, -0.5, 90.0]"));
        assertEquals("regions[1] r1: gates[0] r1-west: arrival 2047.5, 200.0, 1024.5 is not within the blocks "
                        + "x 2048..4095, z 0..2047 of the region",
                refused("[2071.5, 200.0, 1024.5, -90.0]", "[2047.5, 200.0, 1024.5, -90.0]"));
    }

    @Test
    public void aGateLeadsToAnotherRegionOfTheDirectory() throws IOException {
        assertEquals("regions[1] r1: gates[0] r1-west: target_region r9 is not a region of the directory",
                refused("\"target_region\": \"r0\"", "\"target_region\": \"r9\""));
    }

    /** §9: the yaw is a float, so a finite number past the range of a float is no finite yaw. */
    @Test
    public void aYawPastTheRangeOfAFloatIsRefused() throws IOException {
        assertEquals("regions[0] r0: gates[0] r0-east: arrival is not 4 finite numbers",
                refused("[2024.5, 200.0, 1024.5, 90.0]", "[2024.5, 200.0, 1024.5, 1e39]"));
        assertEquals("regions[0] r0: gates[0] r0-east: arrival is not 4 finite numbers",
                refused("[2024.5, 200.0, 1024.5, 90.0]", "[2024.5, 200.0, 1024.5, -1e39]"));
    }

    /**
     * The example with a second gate of r0, r0-south to a region r2, whose frame takes x 2032 to 2047 like
     * r0-east, and z from {@code minZ} to {@code maxZ}.
     */
    private Path withSecondGate(int minZ, int maxZ) throws IOException {
        final String east = "\"arrival\": [2024.5, 200.0, 1024.5, 90.0]}";
        final String west = "\"arrival\": [2071.5, 200.0, 1024.5, -90.0]}]}";
        final String example = example();
        assertTrue(example.contains(east) && example.contains(west));
        final Path file = directory.resolve(ShardDirectory.FILE_NAME);
        Files.writeString(file, example
                .replace(east, east + ", {\"gate_id\": \"r0-south\", \"min\": [2032, 0, " + minZ + "], "
                        + "\"max\": [2047, 255, " + maxZ + "], \"target_region\": \"r2\", "
                        + "\"arrival\": [2024.5, 200.0, 1040.5, 90.0]}")
                .replace(west, west + ", {\"region_id\": \"r2\", \"bounds\": {\"min_x\": 0, \"min_z\": 128, "
                        + "\"max_x\": 127, \"max_z\": 255}, \"gates\": []}"));
        return file;
    }

    /** §19: a block in two frames would send a player through the first one, whichever it walked into. */
    @Test
    public void theFramesOfARegionDoNotOverlap() throws IOException {
        final ShardDirectory.Region r0 = ShardDirectory.load(withSecondGate(1032, 1047), "r0-c1").ownRegion();
        assertEquals("r0-east", Objects.requireNonNull(r0.gateAt(2040, 70, 1031)).gateId());
        assertEquals("r0-south", Objects.requireNonNull(r0.gateAt(2040, 70, 1032)).gateId());
        assertNull(r0.gateAt(2031, 70, 1031));

        assertEquals("regions[0] r0: gates[1] r0-south: the frame x 2032..2047, y 0..255, z 1031..1046 overlaps the "
                + "frame x 2032..2047, y 0..255, z 1016..1031 of gates[0] r0-east", refused(withSecondGate(1031, 1046)));
    }

    @Test
    public void channelsBelongToARegionAndStayInTheirRanges() throws IOException {
        final String channel = "{\"shard_id\": \"r0-c1\", \"region_id\": \"r0\", \"channel\": 1, \"host\": \"127.0.0.1\", \"port\": 25601, \"capacity\": 200}";
        assertEquals("channels[0] r9-c1: region_id r9 is not a region of the directory",
                refused(channel, channel.replace("r0", "r9")));
        assertEquals("channels[0] r0-c01: shard_id is not r0-c1, <region_id>-c<channel>",
                refused(channel, channel.replace("\"r0-c1\"", "\"r0-c01\"")));
        assertEquals("channels[1] r0-c1: channel 1 of r0 is given twice, see channels[0]",
                refused(channel + ",", channel + ", " + channel + ","));
        assertEquals("channels[0] r0-c1000: channel 1000 is not from 1 to 999",
                refused(channel, channel.replace("\"r0-c1\"", "\"r0-c1000\"").replace("\"channel\": 1,", "\"channel\": 1000,")));
        assertEquals("channels[0] r0-c0: channel 0 is not from 1 to 999",
                refused(channel, channel.replace("\"r0-c1\"", "\"r0-c0\"").replace("\"channel\": 1,", "\"channel\": 0,")));
        assertEquals("channels[0] r0-c1: host is not 1 to 253 characters", refused(channel, channel.replace("\"127.0.0.1\"", "\"\"")));
        assertEquals("channels[0] r0-c1: host is not 1 to 253 characters",
                refused(channel, channel.replace("\"127.0.0.1\"", "\"" + "h".repeat(254) + "\"")));
        assertEquals("channels[0] r0-c1: port 0 is not from 1 to 65535", refused(channel, channel.replace("25601", "0")));
        assertEquals("channels[0] r0-c1: port 65536 is not from 1 to 65535", refused(channel, channel.replace("25601", "65536")));
        assertEquals("channels[0] r0-c1: capacity 0 is not from 1 to 500", refused(channel, channel.replace("\"capacity\": 200", "\"capacity\": 0")));
    }

    /** Step (3): the map_id, the bounds of every region and the heights of the frames and arrivals. */
    @Test
    public void theDirectoryIsCheckedAgainstTheMapRelease() {
        final ShardDirectory shards = ShardDirectory.load(resource(ShardDirectory.FILE_NAME), "r0-c1");
        shards.checkAgainst(synth());

        final String bounds = checkRefused(shards, synth("synth", new SynxManifest.Bounds(0, 0, 200, 255),
                new SynxManifest.World(0, 256)));
        assertEquals("regions[1] r1: bounds 128,0,255,127 are not within the bounds 0,0,200,255 of the map release", bounds);

        final String frame = checkRefused(shards, synth("synth", new SynxManifest.Bounds(0, 0, 255, 255),
                new SynxManifest.World(0, 128)));
        assertEquals("regions[0] r0: gates[0] r0-east: the frame y 0..255 is not within the world y 0..127 of the map release",
                frame);

        final ShardDirectory low = new ShardDirectory("synth", List.of(new ShardDirectory.Region("r0",
                new SynxManifest.Bounds(0, 0, 127, 127), List.of(new ShardDirectory.Gate("r0-east",
                new ShardDirectory.Frame(2032, 64, 1016, 2047, 100, 1031), "r1", new Pos(2024.5, 200.0, 1024.5)))),
                new ShardDirectory.Region("r1", new SynxManifest.Bounds(128, 0, 255, 127), List.of())),
                List.of(new ShardDirectory.Channel("r0-c1", "r0", 1, "127.0.0.1", 25601, 200)), "r0-c1");
        final String arrival = checkRefused(low, synth("synth", new SynxManifest.Bounds(0, 0, 255, 255),
                new SynxManifest.World(-64, 256)));
        assertEquals("regions[0] r0: gates[0] r0-east: arrival y 200.0 is not within the world y -64..191 of the map release",
                arrival);
    }

    @Test
    public void theRegionOfAShardIdIsWhatComesBeforeItsLastChannelSeparator() {
        assertEquals("r0", ShardDirectory.regionOfShard("r0-c1"));
        assertEquals("east-coast", ShardDirectory.regionOfShard("east-coast-c12"));
        assertNull(ShardDirectory.regionOfShard("local"));
        assertNull(ShardDirectory.regionOfShard("-c1"));
    }
}
