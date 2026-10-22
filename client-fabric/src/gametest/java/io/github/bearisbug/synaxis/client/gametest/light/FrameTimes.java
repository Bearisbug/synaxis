package io.github.bearisbug.synaxis.client.gametest.light;

import java.util.ArrayList;
import java.util.List;

/**
 * Frame times for the {@code light-frames} mode of {@link LightClientGameTest}: while recording, {@code FrameTimeMixin}
 * hands over the start of every frame, and the mode writes the gaps out as {@code light-frames.csv}. Off, which is
 * always but during that mode, a frame costs one volatile read.
 */
public final class FrameTimes {

    private static volatile boolean recording;
    private static final List<Long> STARTS = new ArrayList<>();

    private FrameTimes() {
    }

    /**
     * The start of a frame, from the render thread.
     *
     * @param nanos {@link System#nanoTime()}
     */
    public static void frame(long nanos) {
        if (!recording) return;
        synchronized (STARTS) {
            STARTS.add(nanos);
        }
    }

    /** Starts recording, dropping what was recorded before. */
    public static void start() {
        synchronized (STARTS) {
            STARTS.clear();
        }
        recording = true;
    }

    /**
     * Stops recording.
     *
     * @return the frame times in nanoseconds, one per gap between frame starts
     */
    public static List<Long> stop() {
        recording = false;
        synchronized (STARTS) {
            final List<Long> times = new ArrayList<>(Math.max(0, STARTS.size() - 1));
            for (int i = 1; i < STARTS.size(); i++) times.add(STARTS.get(i) - STARTS.get(i - 1));
            STARTS.clear();
            return times;
        }
    }
}
