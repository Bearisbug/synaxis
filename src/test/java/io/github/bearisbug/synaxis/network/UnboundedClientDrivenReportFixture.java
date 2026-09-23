package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.exception.ExceptionManager;

/**
 * Test only stand-in for a network class that hands the host one exception per client packet, used
 * by {@code ArchitectureTest} to prove that the "client driven paths do not log per packet" rule
 * covers the report path too: the host picks the handler and need not bound it, so a report per
 * packet can be a stack trace per packet.
 * <p>
 * It sits in {@code network} itself rather than a sub package, which is also what proves the rule
 * watches the whole network stack and not only the decoder and the connection.
 */
public final class UnboundedClientDrivenReportFixture {

    public static void onClientPacket(ExceptionManager exceptions, Throwable failure) {
        exceptions.handleException(failure);
    }
}
