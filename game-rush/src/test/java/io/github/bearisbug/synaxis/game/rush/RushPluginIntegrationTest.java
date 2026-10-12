package io.github.bearisbug.synaxis.game.rush;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.plugin.Admission;
import io.github.bearisbug.synaxis.plugin.AdmissionProvider;
import io.github.bearisbug.synaxis.plugin.AdmissionRequest;
import io.github.bearisbug.synaxis.plugin.DisconnectKeys;
import io.github.bearisbug.synaxis.plugin.PluginAdmission;
import io.github.bearisbug.synaxis.plugin.PluginAnalytics;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.PluginChannels;
import io.github.bearisbug.synaxis.plugin.PluginContext;
import io.github.bearisbug.synaxis.plugin.PluginEvents;
import io.github.bearisbug.synaxis.plugin.PluginScheduler;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;
import io.github.bearisbug.synaxis.plugin.instance.InstanceSpec;
import io.github.bearisbug.synaxis.plugin.instance.ManagedInstance;
import io.github.bearisbug.synaxis.plugin.instance.PluginInstances;
import io.github.bearisbug.synaxis.plugin.play.ActionRequest;
import io.github.bearisbug.synaxis.plugin.play.ActionResult;
import io.github.bearisbug.synaxis.plugin.play.PluginPlay;
import io.github.bearisbug.synaxis.plugin.play.Target;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.timer.TaskSchedule;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sample game as the host enables it ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-010}, §6.7.3, §23):
 * what it builds and registers in {@code onEnable}, what it answers the admission, how the spawn, the
 * actions and the task reach the lobby, and that discovering it needs {@code :play-kit} on the classpath.
 */
@EnvTest
class RushPluginIntegrationTest {

    @TempDir
    Path dataDirectory;

    /**
     * The host side of {@link PluginContext}, reduced to what rush uses, with the scene layer and the
     * managed instances of this package standing in for the host's. The real one lives in
     * {@code :server-app}, which a plugin must not depend on.
     */
    private static final class ProbeContext implements PluginContext {

        final FakePlay play = new FakePlay();
        final FakeInstances instances;
        final List<TaskSchedule> scheduled = new ArrayList<>();
        final List<Map<String, ?>> ledger = new CopyOnWriteArrayList<>();
        private final Env env;
        private final Path dataDirectory;
        @Nullable AdmissionProvider provider;

        ProbeContext(Env env, Path dataDirectory) {
            this.env = env;
            this.dataDirectory = dataDirectory;
            this.instances = new FakeInstances(env, ManagedInstance.State.OPEN);
        }

        @Override
        public ServerProcess process() {
            return env.process();
        }

        @Override
        public PluginEvents events() {
            return new PluginEvents() {
                @Override
                public <E extends Event> void addListener(Class<E> eventType, EventPriority priority,
                                                          boolean ignoreCancelled, Consumer<E> listener) {
                    env.process().eventHandler().addListener(eventType, listener);
                }

                @Override
                public PluginEvents forInstance(Instance instance) {
                    throw new UnsupportedOperationException("rush registers no instance scoped listener");
                }
            };
        }

        @Override
        public PluginScheduler scheduler() {
            return (task, delay, repeat) -> {
                scheduled.add(repeat);
                return env.process().scheduler().buildTask(task).delay(delay).repeat(repeat).schedule();
            };
        }

        @Override
        public PluginChannels channels() {
            throw new UnsupportedOperationException("rush opens no channel of its own");
        }

        @Override
        public PluginAdmission admission() {
            return registered -> this.provider = registered;
        }

        @Override
        public Path dataDirectory() {
            return dataDirectory;
        }

        @Override
        public PluginAnalytics analytics() {
            return (_, attributes) -> ledger.add(attributes);
        }

        @Override
        public PluginPlay play() {
            return play;
        }

        @Override
        public PluginInstances instances() {
            return instances;
        }

        @Override
        public Logger logger() {
            return LoggerFactory.getLogger("plugin.rush");
        }
    }

    @Test
    void theDescriptorAndTheKeysAreTheOnesTheHostAndTheGateRead() {
        final RushPlugin plugin = new RushPlugin();

        assertEquals("rush", plugin.descriptor().id());
        assertEquals(PluginApi.API_VERSION, plugin.descriptor().apiVersion());
        assertEquals(Set.of("rush.scene.lobby", "rush.scene.round", "rush.action.queue", "rush.action.back", "rush.hint",
                "rush.hint.failed", "rush.timer.countdown", "rush.timer.round", "rush.objective", "rush.score",
                "rush.capture", "rush.result", "rush.result.row"), plugin.translationKeys());
    }

    @Test
    void enablingOpensTheLobbyAndTakesTheAdmissionSlot(Env env) {
        final ProbeContext context = enabled(env);

        final InstanceSpec spec = context.instances.specs.getFirst();
        assertEquals("lobby", spec.name());
        assertEquals(Arena.LOBBY_RADIUS, spec.chunkRadius());
        assertEquals(PluginApi.MATCH_DIMENSION_TYPE, spec.dimensionType());
        final FakeManaged lobby = context.instances.last();
        assertEquals(List.of("start"), lobby.requests);
        assertEquals(ManagedInstance.State.RUNNING, lobby.state);
        assertEquals(Set.of("queue", "back"), context.play.actions.keySet());
        assertEquals(List.of(TaskSchedule.tick(1)), context.scheduled, "the lobby is driven by one task, every tick");
        assertTrue(context.ledger.isEmpty(), "enabling wrote a ledger line of rush");

        final AdmissionProvider provider = context.provider;
        assertNotNull(provider, "rush registered no admission provider");
        final UUID connection = UUID.randomUUID();
        final Admission.Accept first = assertInstanceOf(Admission.Accept.class, provider.admit(request(connection)));
        final Admission.Accept again = assertInstanceOf(Admission.Accept.class, provider.admit(request(connection)));
        assertSame(lobby.instance(), first.instance());
        assertSame(first.instance(), again.instance(), "the same connection got another lobby on its second admission");
        assertEquals(0, first.team());

        final Instance ground = lobby.instance();
        assertEquals(Block.STONE, ground.getBlock(0, Arena.GROUND - 1, 0));
        assertEquals(Block.STONE, ground.getBlock(31, Arena.GROUND - 4, -16));
        assertEquals(Block.AIR, ground.getBlock(0, Arena.GROUND, 0));
        assertEquals(Block.BARRIER, ground.getBlock(-16, Arena.GROUND, 0));
        assertEquals(Block.BARRIER, ground.getBlock(31, Arena.GROUND + 3, 5));
        assertEquals(Block.AIR, ground.getBlock(31, Arena.GROUND + 4, 5));
    }

    @Test
    void theAdmissionTurnsPlayersAwayWhileTheLobbyIsNotRunning(Env env) {
        final ProbeContext context = enabled(env);
        context.instances.last().state = ManagedInstance.State.CLOSED;

        final AdmissionProvider provider = context.provider;
        assertNotNull(provider);
        assertEquals(new Admission.Reject(DisconnectKeys.MATCH_UNAVAILABLE), provider.admit(request(UUID.randomUUID())));
    }

    @Test
    void aSpawnIntoTheLobbyEntersItsSceneAndTheActionsAndTheTaskDriveIt(Env env) {
        final ProbeContext context = enabled(env);
        final FakeManaged lobby = context.instances.last();
        final Player alice = env.createConnection(new GameProfile(UUID.randomUUID(), "alice")).connect(lobby.instance(), Arena.SPAWN);
        final Player bob = env.createConnection(new GameProfile(UUID.randomUUID(), "bob")).connect(lobby.instance(), Arena.SPAWN);
        final Player elsewhere = env.createPlayer(env.createFlatInstance(), Arena.SPAWN);

        assertEquals(RushHud.LOBBY, context.play.scene(alice));
        assertEquals(RushHud.hint(0), context.play.widgets(bob).get(RushHud.HINT));
        assertEquals(List.of(), context.play.of(elsewhere), "a spawn outside the lobby reached the lobby");

        final ActionResult queued = context.play.actions.get("queue")
                .handle(alice, new ActionRequest("rush:queue", 1, new Target.None()));
        env.tick();
        assertInstanceOf(ActionResult.Ok.class, queued);
        assertEquals(RushHud.hint(1), context.play.widgets(bob).get(RushHud.HINT));
        assertInstanceOf(ActionResult.Ok.class, context.play.actions.get("back")
                .handle(bob, new ActionRequest("rush:back", 1, new Target.None())));

        alice.getPlayerConnection().disconnect();
        env.tick();
        env.tick();
        assertEquals(RushHud.hint(0), context.play.widgets(bob).get(RushHud.HINT), "a player who left stayed in the queue");
    }

    @Test
    void aConfigurationOutOfRangeFailsTheEnableBeforeAnythingIsBuilt(Env env) {
        final String previous = System.getProperty(RushConfig.MIN_PLAYERS);
        System.setProperty(RushConfig.MIN_PLAYERS, "9");
        final ProbeContext context = new ProbeContext(env, dataDirectory);
        try {
            final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> new RushPlugin().onEnable(context));
            assertEquals("Property 'synaxis.rush.min-players' is not from 1 to 8: 9", refused.getMessage());
        } finally {
            if (previous == null) System.clearProperty(RushConfig.MIN_PLAYERS);
            else System.setProperty(RushConfig.MIN_PLAYERS, previous);
        }
        assertTrue(context.instances.specs.isEmpty(), "a lobby was built for a configuration that is refused");
    }

    /**
     * §23: the host builds every plugin on its classpath before the whitelist picks the ones to enable,
     * so a classpath with the rush jar and without the play kit fails discovery itself, the way
     * {@code PluginHost.discover()} runs it, whatever {@code synaxis.app.plugins} says.
     */
    @Test
    void discoveringRushNeedsThePlayKitOnTheClasspath() throws IOException {
        final List<URL> everything = classpath(_ -> true);
        final List<URL> withoutTheKit = classpath(entry -> !entry.contains(File.separator + "play-kit" + File.separator));
        assertTrue(withoutTheKit.size() < everything.size(), "the play kit is not on this classpath: " + everything);

        try (URLClassLoader loader = new URLClassLoader(withoutTheKit.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            final Throwable failure = assertThrows(Throwable.class, () -> discover(loader));
            assertTrue(causes(failure).stream().anyMatch(cause -> cause instanceof NoClassDefFoundError
                            && String.valueOf(cause.getMessage()).contains("io/github/bearisbug/synaxis/game/playkit/LocalQueue")),
                    () -> "discovery failed for another reason: " + causes(failure));
        }
        try (URLClassLoader loader = new URLClassLoader(everything.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            assertEquals(List.of(RushPlugin.class.getName()), discover(loader));
        }
    }

    private ProbeContext enabled(Env env) {
        final ProbeContext context = new ProbeContext(env, dataDirectory);
        new RushPlugin().onEnable(context);
        return context;
    }

    private static AdmissionRequest request(UUID connection) {
        return new AdmissionRequest(connection, "tester", 0, 0, UUID.randomUUID(), InetAddress.getLoopbackAddress());
    }

    private static List<URL> classpath(Predicate<String> keep) throws MalformedURLException {
        final List<URL> urls = new ArrayList<>();
        for (final String entry : Pattern.compile(Pattern.quote(File.pathSeparator))
                .splitAsStream(System.getProperty("java.class.path")).toList()) {
            if (!entry.isEmpty() && keep.test(entry)) urls.add(Path.of(entry).toUri().toURL());
        }
        return urls;
    }

    /**
     * What {@code PluginHost.discover()} does, in a loader of its own: every provider on the classpath
     * is built.
     */
    private static List<String> discover(ClassLoader loader) {
        final Class<?> service;
        try {
            service = loader.loadClass(SynaxisPlugin.class.getName());
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
        assertTrue(service != SynaxisPlugin.class, "the isolated loader handed back the test's own class");
        final List<String> names = new ArrayList<>();
        ServiceLoader.load(service, loader).stream().map(ServiceLoader.Provider::get)
                .forEach(plugin -> names.add(plugin.getClass().getName()));
        return names;
    }

    private static List<Throwable> causes(Throwable failure) {
        final List<Throwable> chain = new ArrayList<>();
        for (Throwable cause = failure; cause != null && !chain.contains(cause); cause = cause.getCause()) chain.add(cause);
        return chain;
    }
}
