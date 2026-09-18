package io.github.bearisbug.synaxis.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stands in for a host class that writes a line per inbound payload. Only ever imported as a
 * synthetic {@code JavaClasses} by {@link ClientDrivenLogArchitectureTest}, never scanned as part
 * of the host.
 */
final class UnboundedHostLogFixture {

    private static final Logger LOGGER = LoggerFactory.getLogger(UnboundedHostLogFixture.class);

    void onPayload(String channel) {
        LOGGER.warn("A payload arrived on {}", channel);
    }
}
