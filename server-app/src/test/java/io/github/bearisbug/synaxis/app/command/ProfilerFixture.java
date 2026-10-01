package io.github.bearisbug.synaxis.app.command;

import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.TimeUnit;

/**
 * The JVM {@link ProfilerPrivacyTest} starts with a secret on its command line, which only a JVM
 * of its own can have from its start: one recording of {@value Profiler#MIN_SECONDS} s, then the
 * path of the file on the last line of the output.
 */
final class ProfilerFixture {

    static final String WRITTEN = "written ";

    private ProfilerFixture() {
    }

    /**
     * @param args the directory of the recording
     */
    public static void main(String[] args) throws Exception {
        final Profiler.Start start = new Profiler(Path.of(args[0]), "fixture", Clock.systemUTC()).start(Profiler.MIN_SECONDS);
        if (!(start instanceof Profiler.Started started)) throw new IllegalStateException("not started: " + start);
        System.out.println(WRITTEN + started.written().get(30, TimeUnit.SECONDS));
    }
}
