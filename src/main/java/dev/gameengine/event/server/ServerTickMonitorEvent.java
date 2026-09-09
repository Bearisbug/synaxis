package dev.gameengine.event.server;

import dev.gameengine.event.Event;
import dev.gameengine.monitoring.TickMonitor;

public final class ServerTickMonitorEvent implements Event {
    private final TickMonitor tickMonitor;

    public ServerTickMonitorEvent(TickMonitor tickMonitor) {
        this.tickMonitor = tickMonitor;
    }

    public TickMonitor getTickMonitor() {
        return tickMonitor;
    }
}
