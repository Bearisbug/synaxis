package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.app.instance.InstanceRegistry;
import io.github.bearisbug.synaxis.app.moderation.HostModeration;
import io.github.bearisbug.synaxis.app.permission.HostPermissions;
import io.github.bearisbug.synaxis.app.scene.SceneService;
import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.app.store.PluginPlayerData;
import io.github.bearisbug.synaxis.app.transfer.PluginTransfers;
import io.github.bearisbug.synaxis.app.transfer.TicketVerifier;
import io.github.bearisbug.synaxis.app.transfer.TransferCooldowns;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.game.protocol.play.PlayProtocol;
import io.github.bearisbug.synaxis.plugin.AdmissionProvider;
import io.github.bearisbug.synaxis.plugin.PayloadHandler;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;
import io.github.bearisbug.synaxis.plugin.instance.EndCause;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Discovery and lifecycle of the plugins of one process (REQ-M2-003).
 * <p>
 * Plugins are compile time only and declare no dependencies, so there is no dependency resolution:
 * the order {@link ServiceLoader} hands them over is the enable order, and disabling walks it
 * backwards. Several plugins may be enabled in one process, picked by {@code synaxis.app.plugins};
 * what has to come back with a second plugin in a production process, the dependency resolution and
 * a floor per plugin in the disable budget, is {@code docs/DESIGN-PLAY.md} D2. A plugin goes through
 * the states of {@code docs/DESIGN-M2.md} §11.2: constructed, then enabled or failed, and from
 * enabled to disabled. Hot reloading is not supported.
 */
final class PluginHost {

    private static final Logger LOGGER = LoggerFactory.getLogger(PluginHost.class);

    private static final long DISABLE_BUDGET_SECONDS = 5;

    private final ServerProcess process;
    private final Path pluginsDirectory;
    private final EventLedger ledger;
    private final PluginChannelRouter router = new PluginChannelRouter();
    private final AdmissionRegistry admission = new AdmissionRegistry();
    private final EventNode<Event> hostNode = EventNode.all("synaxis-host");
    private final HandshakeGate handshake;
    private final PlayerStore store;
    private final HostPermissions permissions;
    private final HostModeration moderation;
    private final TabListSlot tabList;
    private final TransferCooldowns cooldowns;
    private final SceneService scenes;
    private final InstanceRegistry instances;
    private final List<LoadedPlugin> enabled = new ArrayList<>();
    /** What the {@code onEnable} of each plugin that failed threw, by plugin id, for the refusal summary. */
    private final Map<String, String> failedToEnable = new LinkedHashMap<>();

    /**
     * How many plugins this host took on, whether or not their {@code onEnable} got anywhere. The
     * startup sequence reads it to tell "nobody was going to provide gameplay" from "somebody was
     * and came up wrong", which are the core mode and a refusal (API-M2-010 step 5).
     */
    private int attemptedPlugins;

    /**
     * A host whose player data lives in memory, for as long as the process does.
     */
    PluginHost(ServerProcess process, Path pluginsDirectory, EventLedger ledger) {
        this(process, pluginsDirectory, ledger, PlayerStore.inMemory(process, ledger::record));
    }

    PluginHost(ServerProcess process, Path pluginsDirectory, EventLedger ledger, PlayerStore store) {
        this(process, pluginsDirectory, ledger, store, null, HostPermissions.withoutDatabase(process));
    }

    PluginHost(ServerProcess process, Path pluginsDirectory, EventLedger ledger, PlayerStore store,
               @Nullable TicketVerifier tickets, HostPermissions permissions) {
        this(process, pluginsDirectory, ledger, store, tickets, permissions, HostModeration.withoutDatabase(process));
    }

    PluginHost(ServerProcess process, Path pluginsDirectory, EventLedger ledger, PlayerStore store,
               @Nullable TicketVerifier tickets, HostPermissions permissions, HostModeration moderation) {
        this(process, pluginsDirectory, ledger, store, tickets, permissions, moderation,
                TransferCooldowns.fromSystemProperties());
    }

    /**
     * @param cooldowns the transfer cooldowns of the process, the table its {@code TicketIssuer} and
     *                  {@code TicketVerifier} use ({@code docs/DESIGN-PROD.md} API-PROD-021)
     */
    PluginHost(ServerProcess process, Path pluginsDirectory, EventLedger ledger, PlayerStore store,
               @Nullable TicketVerifier tickets, HostPermissions permissions, HostModeration moderation,
               TransferCooldowns cooldowns) {
        this.process = process;
        this.pluginsDirectory = pluginsDirectory;
        this.ledger = ledger;
        this.store = store;
        this.permissions = permissions;
        this.moderation = moderation;
        this.tabList = new TabListSlot(process.connection());
        this.cooldowns = cooldowns;
        this.handshake = new HandshakeGate(HandshakeGate.phase(), HandshakeGate.BUDGET, ledger);
        // In the core mode and on a map shard the host holds the admission slot under the reserved id, which
        // makes no plugin the scene owner
        this.scenes = new SceneService(() -> {
            final String holder = admission.pluginId();
            return PluginDescriptors.RESERVED_ID.equals(holder) ? null : holder;
        }, ledger::record, System::nanoTime);
        // Built on the thread that goes on to enable the plugins, which is the startup thread a
        // managed instance created in onEnable is prepared on (docs/DESIGN-PLAY.md §6.1)
        this.instances = new InstanceRegistry(process, ledger::record, scenes, Thread.currentThread(),
                InstanceRegistry.Durations.DEFAULT, System::nanoTime);
        process.eventHandler().addChild(hostNode);
        router.attach(hostNode);
        // Before the handshake: a banned connection is turned away before anything else is asked of it
        moderation.attach(hostNode);
        handshake.attach(hostNode);
        // The handshake speaks over a channel of the reserved namespace, so it is registered the
        // same way the core mode's diagnostic channel is: through the one payload listener of the
        // router, which is what keeps "an unknown channel is dropped" true for it as well. It is the
        // one channel a client speaks on before the play state in a gameplay process.
        registerHostChannel(HandshakeGate.CHANNEL, handshake::receive, true);
        new AdmissionGate(admission, handshake, ledger, store, tickets, permissions).attach(hostNode);
        store.attach(hostNode);
        permissions.attach(hostNode);
        // The scene layer's channel goes through the router the same way the handshake's does
        registerHostChannel(PlayProtocol.CHANNEL, scenes::receive, false);
        scenes.attach(hostNode);
    }

    /**
     * Makes the client mod handshake mandatory, which the startup sequence does for a gameplay
     * launch only (REQ-M2-008). Called before the port is bound.
     */
    void requireHandshake() {
        handshake.require();
    }

    /**
     * Finds the compile time plugins on the classpath (ADR-M2-006). Every provider is built, the ones
     * {@code synaxis.app.plugins} leaves out too: its descriptor is what the selection reads.
     *
     * @return the discovered plugins, in {@link ServiceLoader} order
     * @throws IllegalArgumentException if a provider cannot be built, which refuses the start: its class is
     *                                  not found, or its constructor throws or misses a class while it runs,
     *                                  and the message names its class; or a class it extends or implements is
     *                                  missing, and the message names that class
     */
    static List<SynaxisPlugin> discover() {
        try {
            return ServiceLoader.load(SynaxisPlugin.class).stream()
                    .map(ServiceLoader.Provider::get)
                    .toList();
        } catch (ServiceConfigurationError e) {
            throw new IllegalArgumentException("Could not load the plugins on the classpath: " + e.getMessage(), e);
        } catch (LinkageError e) {
            // ServiceLoader looks a provider class up before it wraps a failure: a superclass or an
            // interface missing from the classpath comes out as it is
            throw new IllegalArgumentException("Could not load the plugins on the classpath: " + e, e);
        }
    }

    /**
     * Enables the selected plugins, in the given order.
     * <p>
     * Every selected descriptor is validated before any plugin is enabled, so a refused descriptor
     * leaves the process with nothing to undo. Two of them with the same id are refused the same way:
     * the channels, the admission slot, the actions and the managed instances are all kept by plugin
     * id, and the teardown of one would take back the other's. A plugin whose {@code onEnable} throws
     * is torn down and the remaining ones are enabled anyway; whether the process can still serve is
     * then decided by {@link #hasAdmissionProvider()}.
     * <p>
     * Call it on the thread that built this host and before the port is bound: a managed instance a
     * plugin creates in {@code onEnable} is prepared in place only on that thread
     * ({@code docs/DESIGN-PLAY.md} §6.1).
     *
     * @param plugins     the discovered plugins
     * @param selectedIds the ids to enable, empty for all of them
     * @throws IllegalArgumentException if a discovered plugin reports no descriptor or no id, which the
     *                                  selection needs of every one of them, if a selected descriptor is
     *                                  refused, or if two of them have the same id; the message names the
     *                                  plugin class
     */
    void enable(List<SynaxisPlugin> plugins, Set<String> selectedIds) {
        final List<String> discoveredIds = plugins.stream().map(PluginDescriptors::id).toList();
        final List<String> unknownIds = selectedIds.stream().filter(id -> !discoveredIds.contains(id)).sorted().toList();
        if (!unknownIds.isEmpty())
            // A selection nothing answers to is not fatal (the operator may be excluding every
            // plugin on purpose), but it is also how a typo turns into a launch that serves the
            // core world instead of the gameplay that was asked for (API-M2-010 step 5).
            LOGGER.warn("No plugin answers to selected ids {}: discovered plugin_ids={}.", unknownIds, discoveredIds);
        final List<SynaxisPlugin> selected = plugins.stream()
                .filter(plugin -> selectedIds.isEmpty() || selectedIds.contains(plugin.descriptor().id()))
                .toList();
        for (SynaxisPlugin plugin : selected) PluginDescriptors.validate(plugin);
        final Map<String, List<String>> classesById = new LinkedHashMap<>();
        for (SynaxisPlugin plugin : selected)
            classesById.computeIfAbsent(plugin.descriptor().id(), _ -> new ArrayList<>()).add(plugin.getClass().getName());
        for (Map.Entry<String, List<String>> declared : classesById.entrySet()) {
            if (declared.getValue().size() > 1)
                throw new IllegalArgumentException("Refused plugin id \"" + declared.getKey() + "\": declared by "
                        + declared.getValue() + ", and a plugin id is unique in the process");
        }
        this.attemptedPlugins += selected.size();
        for (SynaxisPlugin plugin : selected) enableOne(plugin);
    }

    private void enableOne(SynaxisPlugin plugin) {
        final PluginDescriptor descriptor = plugin.descriptor();
        final String id = descriptor.id();
        final PluginSchedulerImpl scheduler = new PluginSchedulerImpl(id, process.scheduler());
        final PluginCommandsImpl commands = new PluginCommandsImpl(id, process);
        final LoadedPlugin loaded = new LoadedPlugin(plugin, id, new PluginEventsImpl(id, hostNode), scheduler, commands);
        final Path dataDirectory = pluginsDirectory.resolve(id);
        final PluginPlayerData playerData = store.forPlugin(id);
        final long start = System.nanoTime();
        try {
            Files.createDirectories(dataDirectory);
            plugin.onEnable(new PluginContextImpl(process,
                    loaded.events(), scheduler,
                    new PluginChannelsImpl(id, router), new PluginAdmissionImpl(id, admission),
                    dataDirectory, ledger::record, playerData, playerData, permissions, moderation, commands,
                    new PluginTabListImpl(id, tabList), process.trackingRanges()::setDefault,
                    new PluginTransfers(id, cooldowns), scenes.forPlugin(id), instances.forPlugin(id),
                    LoggerFactory.getLogger("plugin." + id)));
        } catch (Throwable throwable) {
            playerData.discard();
            failedToEnable.put(id, throwable.toString());
            LOGGER.error("Plugin failed to enable: plugin_id={}", id, throwable);
            tearDown(loaded, EndCause.PLUGIN_DISABLED);
            return;
        }
        // Actions are registered in onEnable only (API-PLAY-005)
        scenes.sealActions(id);
        playerData.enabled();
        enabled.add(loaded);
        final long enableMs = (System.nanoTime() - start) / 1_000_000L;
        LOGGER.info("Plugin enabled: plugin_id={} version={} enable_ms={}", id, descriptor.version(), enableMs);
        final Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("plugin_id", id);
        attributes.put("version", descriptor.version());
        attributes.put("enable_ms", enableMs);
        ledger.record("plugin_enabled", attributes);
    }

    /**
     * Disables every enabled plugin in reverse enable order. Registered as the shutdown task of the
     * engine scheduler, which drains it on the tick scheduler thread between two ticks, or on the
     * stop thread when the ticker does not pick it up; safe to call twice. Each {@code onDisable}
     * runs on a thread of its own that owns no tick thread (API-PROD-007).
     * <p>
     * The plugin side of the shutdown gets {@value #DISABLE_BUDGET_SECONDS} s
     * ({@code docs/DESIGN-PROD.md} §11.4, API-PROD-007). A plugin still inside {@code onDisable} when the
     * budget is spent is left behind on its own daemon thread and its capabilities are taken back
     * anyway: a blocking plugin must not be able to keep the process from exiting. The budget is
     * shared by the whole loop and no plugin gets a reserved slice of it: a plugin whose turn comes
     * once an earlier one has spent it still has its {@code onDisable} started on a daemon thread, is
     * not waited for and has its capabilities taken back at once, and its line says the budget was
     * already spent rather than that it ran over. With a second plugin in a production process the
     * budget has to get a floor per plugin.
     * <p>
     * A teardown that throws, such as the forced close of a managed instance whose chunk loader fails,
     * is written as one ERROR line, and the plugins after it are disabled and taken back all the same.
     */
    void disableAll() {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DISABLE_BUDGET_SECONDS);
        for (int i = enabled.size() - 1; i >= 0; i--) {
            final LoadedPlugin loaded = enabled.get(i);
            LOGGER.info("Disabling plugin: plugin_id={}", loaded.id());
            try {
                disableWithin(loaded, deadline);
                tearDown(loaded, EndCause.SHUTDOWN);
            } catch (Throwable throwable) {
                LOGGER.error("Plugin teardown failed: plugin_id={}", loaded.id(), throwable);
            }
        }
        enabled.clear();
    }

    private static void disableWithin(LoadedPlugin loaded, long deadline) {
        final long remaining = deadline - System.nanoTime();
        final Thread thread = Thread.ofPlatform().daemon().name("plugin-disable-" + loaded.id()).start(() -> {
            try {
                loaded.plugin().onDisable();
            } catch (Throwable throwable) {
                LOGGER.error("Plugin failed to disable: plugin_id={}", loaded.id(), throwable);
            }
        });
        if (remaining <= 0) {
            LOGGER.error("The {} s plugin shutdown budget was already spent before this plugin, onDisable not awaited: "
                    + "plugin_id={}. Taking its capabilities back and going on with the shutdown.",
                    DISABLE_BUDGET_SECONDS, loaded.id());
            return;
        }
        try {
            thread.join(Duration.ofNanos(Math.max(1L, deadline - System.nanoTime())));
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive())
            LOGGER.error("Plugin is still in onDisable after the {} s budget: plugin_id={}. Taking its capabilities "
                    + "back and going on with the shutdown.", DISABLE_BUDGET_SECONDS, loaded.id());
    }

    int attemptedPluginCount() {
        return attemptedPlugins;
    }

    boolean hasAdmissionProvider() {
        return admission.isRegistered();
    }

    /**
     * Takes the admission slot for the host itself, as the core mode and a map mounted shard do: the
     * reserved id belongs to no plugin, so nothing ever takes this registration back.
     *
     * @param provider the provider to register
     */
    void registerHostAdmission(AdmissionProvider provider) {
        admission.register(PluginDescriptors.RESERVED_ID, provider);
    }

    /**
     * Registers a channel of the host's reserved namespace: handshake and scene layer always, the echo in core mode.
     *
     * @param name                 the channel id, inside the reserved namespace
     * @param handler              the inbound handler
     * @param configurationAllowed whether the handler also gets the payloads of a connection that is not
     *                             in the play state, as the handshake does (API-M2-001)
     */
    void registerHostChannel(String name, PayloadHandler handler, boolean configurationAllowed) {
        final String channelId = router.register(PluginDescriptors.RESERVED_ID, name, handler, configurationAllowed);
        LOGGER.info("Registered channel: plugin_id={} channel={}", PluginDescriptors.RESERVED_ID, channelId);
    }

    @Nullable AdmissionProvider admissionProvider() {
        return admission.provider();
    }

    /**
     * The host node {@code synaxis-host}, which the default play layer of {@code docs/DESIGN-PROD.md}
     * {@code API-PROD-031} attaches to: its own listeners run before the plugin priority nodes below it.
     *
     * @return the host node
     */
    EventNode<Event> hostNode() {
        return hostNode;
    }

    /**
     * What the {@code onEnable} of each plugin that failed threw, for the summary of a refused launch.
     *
     * @return the failures by plugin id, in enable order
     */
    Map<String, String> failedToEnable() {
        return new LinkedHashMap<>(failedToEnable);
    }

    List<String> enabledPluginIds() {
        return enabled.stream().map(LoadedPlugin::id).toList();
    }

    /**
     * Read by a crash report, from whichever thread is failing: a report written while a plugin is
     * being enabled or disabled can fail to list them, and prints that failure instead.
     *
     * @return one {@code id version} entry per enabled plugin, in enable order
     */
    List<String> enabledPluginVersions() {
        return enabled.stream().map(loaded -> loaded.id() + " " + loaded.plugin().descriptor().version()).toList();
    }

    /**
     * @param cause what the instances of the plugin still open are closed with: the plugin was torn
     *              down after its {@code onEnable} threw, or the process stops
     */
    private void tearDown(LoadedPlugin loaded, EndCause cause) {
        loaded.events().detach();
        loaded.scheduler().cancelAll();
        router.unregisterAll(loaded.id());
        admission.unregister(loaded.id());
        loaded.commands().unregisterAll();
        tabList.unregister(loaded.id());
        scenes.reclaim(loaded.id());
        instances.reclaim(loaded.id(), cause);
        LOGGER.info("Took back the capabilities of plugin_id={}: event node, tasks, channels, admission provider, commands, "
                + "tab list visibility, actions, managed instances", loaded.id());
    }
}
