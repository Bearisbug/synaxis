package io.github.bearisbug.synaxis.app.shard;

/**
 * A row of {@code shard_heartbeat} as {@link HeartbeatReader} reads it ({@code docs/DESIGN-M3.md}
 * {@code API-M3-010}).
 *
 * @param shardId   the shard
 * @param regionId  its region
 * @param channel   its channel number
 * @param host      the address clients connect to
 * @param port      the port clients connect to
 * @param online    the seats taken when it was written
 * @param capacity  the seats it has
 * @param accepting whether its login gate was open
 * @param live      whether it was written less than 15 s ago, by the database clock
 */
public record HeartbeatRow(String shardId, String regionId, int channel, String host, int port,
                           int online, int capacity, boolean accepting, boolean live) {
}
