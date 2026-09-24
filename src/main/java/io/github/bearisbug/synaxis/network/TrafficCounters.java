package io.github.bearisbug.synaxis.network;

import org.jetbrains.annotations.ApiStatus;

import java.util.concurrent.atomic.LongAdder;

/**
 * What every socket connection of a process moved since it started ({@code docs/DESIGN-PROD.md}
 * {@code API-PROD-015}): packets and bytes on the wire in both directions, and the time spent in the
 * stream cipher. Written by the read and write threads of the connections, read by the host's
 * metrics endpoint; each figure only ever grows.
 * <p>
 * Bytes are wire bytes, framing, compression and encryption included: inbound as they came off the
 * socket, outbound as they were framed for it.
 */
@ApiStatus.Internal
public final class TrafficCounters {

    private final LongAdder packetsIn = new LongAdder();
    private final LongAdder packetsOut = new LongAdder();
    private final LongAdder bytesIn = new LongAdder();
    private final LongAdder bytesOut = new LongAdder();
    private final LongAdder decryptNanos = new LongAdder();
    private final LongAdder encryptNanos = new LongAdder();
    private final LongAdder backlogKicks = new LongAdder();

    /** Counts bytes read off a socket. */
    public void received(long bytes) {
        bytesIn.add(bytes);
    }

    /** Counts packets read off a socket. */
    public void read(int packets) {
        packetsIn.add(packets);
    }

    /** Counts one packet framed for a socket, and its bytes. */
    public void sent(long bytes) {
        packetsOut.increment();
        bytesOut.add(bytes);
    }

    /** Counts time spent decrypting. */
    public void decrypted(long nanos) {
        decryptNanos.add(nanos);
    }

    /** Counts time spent encrypting. */
    public void encrypted(long nanos) {
        encryptNanos.add(nanos);
    }

    /** Counts a connection kicked for its outbound backlog ({@code API-PROD-020}). */
    public void backlogKicked() {
        backlogKicks.increment();
    }

    /** Connections kicked for their outbound backlog. */
    public long backlogKicks() {
        return backlogKicks.sum();
    }

    /** Packets read off the sockets, the ones a connection dropped as malformed excluded. */
    public long packetsIn() {
        return packetsIn.sum();
    }

    /** Packets written to the sockets' buffers. */
    public long packetsOut() {
        return packetsOut.sum();
    }

    /** Bytes read off the sockets. */
    public long bytesIn() {
        return bytesIn.sum();
    }

    /** Bytes framed for the sockets. */
    public long bytesOut() {
        return bytesOut.sum();
    }

    /** Time spent decrypting inbound bytes, in nanoseconds. */
    public long decryptNanos() {
        return decryptNanos.sum();
    }

    /** Time spent encrypting outbound bytes, in nanoseconds. */
    public long encryptNanos() {
        return encryptNanos.sum();
    }
}
