package io.github.bearisbug.synaxis.monitoring;

public class TickMonitor {

    private final double tickTime;
    private final double acquisitionTime;

    /**
     * Creates the monitoring data of one tick.
     *
     * @param tickTime        the duration of the tick, in milliseconds
     * @param acquisitionTime the acquisition wait of the tick, in milliseconds
     */
    public TickMonitor(double tickTime, double acquisitionTime) {
        this.tickTime = tickTime;
        this.acquisitionTime = acquisitionTime;
    }

    /**
     * How long this tick took, in milliseconds: from the start of the tick to just before
     * {@code ServerTickMonitorEvent} is called, so the listeners of that event are not counted.
     *
     * @return the duration of the tick, in milliseconds
     */
    public double getTickTime() {
        return tickTime;
    }

    /**
     * How long this tick waited to acquire, in milliseconds ({@code docs/DESIGN-PROD.md}
     * {@code API-PROD-016}): the time the thread running the tick spent blocked on a lock of the
     * acquisition protocol outside the parallel part (counted when that thread is an engine thread,
     * as the tick scheduler is), plus the longest such time of a single tick thread in the parallel
     * part. The two parts do not overlap in time, so the value is at most {@link #getTickTime()} and
     * bounds the waiting on any one thread's path through the tick; 0 when no acquisition had to wait.
     *
     * @return the acquisition wait of the tick, in milliseconds
     */
    public double getAcquisitionTime() {
        return acquisitionTime;
    }
}
