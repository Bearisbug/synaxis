package io.github.bearisbug.synaxis.listener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test only stand-in for a listener that writes a WARN line per client packet, used by
 * {@code ArchitectureTest} to prove that the "client driven paths do not log per packet" rule
 * really fires.
 * <p>
 * The engine cannot carry such a class on purpose, and the rule scans main sources only, so the
 * offender has to be declared here.
 */
public final class UnboundedClientDrivenLogFixture {
    private static final Logger LOGGER = LoggerFactory.getLogger(UnboundedClientDrivenLogFixture.class);

    public static void onClientPacket() {
        LOGGER.warn("one line per packet");
    }
}
