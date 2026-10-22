package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.plugin.Admission;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.PluginContext;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;
import io.github.bearisbug.synaxis.plugin.instance.InstanceSpec;
import io.github.bearisbug.synaxis.plugin.instance.ManagedInstance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * A plugin that puts a process into the gameplay mode and does nothing else: it registers an admission provider that
 * lets every connection into one managed instance, a stone floor of 5 × 5 chunks, spawning on it.
 * <p>
 * A test lists it with a services file of its own in front of the class path, as {@code ShutdownExitLaunchTest}
 * does, and {@code ./gradlew :server-app:gameplayFixtureArgs} writes {@code build/gameplay-fixture.args}, which
 * does the same for {@code java @server-app/build/gameplay-fixture.args … Main}. Its services file must not go into
 * {@code src/test/resources}: every process a test starts on the test class path in the auto mode would then find
 * it and serve gameplay instead of the core world.
 * <p>
 * With the system property {@value #RELEASE} naming a file, {@code onEnable} returns only once that file exists, so a
 * test keeps the process starting, its plugins being enabled and the port not bound, for as long as it needs. After
 * {@value #RELEASE_TIMEOUT_SECONDS} s without the file it throws, the plugin fails to enable and the start is
 * refused, so a test that fails before it releases the plugin leaves no process waiting.
 */
public final class GameplayFixturePlugin implements SynaxisPlugin {

    /** The plugin id. */
    public static final String ID = "gameplay";

    /** The system property naming the file {@code onEnable} waits for. */
    public static final String RELEASE = "gameplay-fixture.release";

    /** How long {@code onEnable} waits for the file. */
    static final long RELEASE_TIMEOUT_SECONDS = 60;

    /** The top of the floor, which fills y 0 and nothing above. */
    private static final int FLOOR_HEIGHT = 1;

    /** The spawn point, on the floor in the middle of chunk 0, 0. */
    private static final Pos SPAWN = new Pos(8.5, FLOOR_HEIGHT, 8.5);

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(ID, "1.0.0", PluginApi.API_VERSION);
    }

    @Override
    public void onEnable(PluginContext context) {
        final String release = System.getProperty(RELEASE);
        if (release != null) awaitRelease(Path.of(release));
        // Created on the startup thread the instance comes back open and registered, which the mode decision counts
        final ManagedInstance world = context.instances().create(new InstanceSpec("world", 2,
                unit -> unit.modifier().fillHeight(0, FLOOR_HEIGHT, Block.STONE)));
        world.start();
        context.events().addListener(AsyncPlayerConfigurationEvent.class,
                event -> event.getPlayer().setRespawnPoint(SPAWN));
        context.admission().register(_ -> new Admission.Accept(world.instance()));
    }

    private static void awaitRelease(Path file) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(RELEASE_TIMEOUT_SECONDS);
        while (!Files.exists(file)) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException(file + " did not appear within " + RELEASE_TIMEOUT_SECONDS + " s");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for " + file, e);
            }
        }
    }
}
