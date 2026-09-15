package io.github.bearisbug.synaxis.event.server;

import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.monitoring.TickMonitor;

public final class ServerTickMonitorEvent implements Event {
    private final TickMonitor tickMonitor;

    public ServerTickMonitorEvent(TickMonitor tickMonitor) {
        this.tickMonitor = tickMonitor;
    }

    public TickMonitor getTickMonitor() {
        return tickMonitor;
    }
}
