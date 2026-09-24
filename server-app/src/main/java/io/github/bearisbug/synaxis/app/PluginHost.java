package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.app.moderation.HostModeration;
import io.github.bearisbug.synaxis.app.permission.HostPermissions;
import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.app.store.PluginPlayerData;
import io.github.bearisbug.synaxis.app.transfer.TicketVerifier;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.plugin.AdmissionProvider;
import io.github.bearisbug.synaxis.plugin.PayloadHandler;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;
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
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Discovery and lifecycle of the plugins of one process (REQ-M2-003).
 * <p>
 * M2 has a single compile time plugin, so there is no dependency resolution: the order
 * {@link ServiceLoader} hands them over is the enable order, and disabling walks it backwards.
 * Hot reloading is not supported either, a plugin only ever goes constructed, enabled, disabled.
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
    private final List<LoadedPlugin> enabled = new ArrayList<>();

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
        this.process = process;
        this.pluginsDirectory = pluginsDirectory;
        this.ledger = ledger;
        this.store = store;
        this.permissions = permissions;
        this.moderation = moderation;
        this.handshake = new HandshakeGate(HandshakeGate.phase(), HandshakeGate.BUDGET, ledger);
        process.eventHandler().addChild(hostNode);
        router.attach(hostNode);
        // Before the handshake: a banned connection is turned away before anything else is asked of it
        moderation.attach(hostNode);
        handshake.attach(hostNode);
        // The handshake speaks over a channel of the reserved namespace, so it is registered the
        // same way the core mode's diagnostic channel is: through the one payload listener of the
        // router, which is what keeps "an unknown channel is dropped" true for it as well.
        registerHostChannel(HandshakeGate.CHANNEL, handshake::receive);
        new AdmissionGate(admission, handshake, ledger, store, tickets, permissions).attach(hostNode);
        store.attach(hostNode);
        permissions.attach(hostNode);
    }

    /**
     * Makes the client mod handshake mandatory, which the startup sequence does for a gameplay
     * launch only (REQ-M2-008). Called before the port is bound.
     */
    void requireHandshake() {
        handshake.require();
    }

    /**
     * Finds the compile time plugins on the classpath (ADR-M2-006).
     *
     * @return the discovered plugins, in {@link ServiceLoader} order
     */
    static List<SynaxisPlugin> discover() {
        return ServiceLoader.load(SynaxisPlugin.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
    }

    /**
     * Enables the selected plugins, in the given order.
     * <p>
     * Every selected descriptor is validated before any plugin is enabled, so a refused descriptor
     * leaves the process with nothing to undo. A plugin whose {@code onEnable} throws is torn down
     * and the remaining ones are enabled anyway; whether the process can still serve is then
     * decided by {@link #hasAdmissionProvider()}.
     *
     * @param plugins     the discovered plugins
     * @param selectedIds the ids to enable, empty for all of them
     * @throws IllegalArgumentException if a selected descriptor is refused
     */
    void enable(List<SynaxisPlugin> plugins, Set<String> selectedIds) {
        final List<String> discoveredIds = plugins.stream().map(plugin -> plugin.descriptor().id()).toList();
        final List<String> unknownIds = selectedIds.stream().filter(id -> !discoveredIds.contains(id)).sorted().toList();
        if (!unknownIds.isEmpty())
            // A selection nothing answers to is not fatal (the operator may be excluding every
            // plugin on purpose), but it is also how a typo turns into a launch that serves the
            // core world instead of the gameplay that was asked for (API-M2-010 step 5).
            LOGGER.warn("No plugin answers to selected ids {}: discovered plugin_ids={}.", unknownIds, discoveredIds);
        final List<SynaxisPlugin> selected = plugins.stream()
                .filter(plugin -> selectedIds.isEmpty() || selectedIds.contains(plugin.descriptor().id()))
                .toList();
        for (SynaxisPlugin plugin : selected) PluginDescriptors.validate(plugin.descriptor());
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
                    process.trackingRanges()::setDefault, LoggerFactory.getLogger("plugin." + id)));
        } catch (Throwable throwable) {
            playerData.discard();
            LOGGER.error("Plugin failed to enable: plugin_id={}", id, throwable);
            tearDown(loaded);
            return;
        }
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
     * engine scheduler, which drains it once the tick threads are joined; safe to call twice.
     * <p>
     * The plugin side of the shutdown gets the {@value #DISABLE_BUDGET_SECONDS} s of the 15 s
     * budget of {@code docs/DESIGN-M2.md} §17. A plugin still inside {@code onDisable} when the
     * budget is spent is left behind on its own daemon thread and its capabilities are taken back
     * anyway: a blocking plugin must not be able to keep the process from exiting. The budget is
     * shared by the whole loop and no plugin gets a reserved slice of it, so once a plugin has spent
     * it the ones after it are waited on no longer — with a second plugin that turns into "one
     * plugin hangs, the rest are dropped silently", and the budget then has to get a floor per
     * plugin.
     */
    void disableAll() {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DISABLE_BUDGET_SECONDS);
        for (int i = enabled.size() - 1; i >= 0; i--) {
            final LoadedPlugin loaded = enabled.get(i);
            LOGGER.info("Disabling plugin: plugin_id={}", loaded.id());
            disableWithin(loaded, deadline);
            tearDown(loaded);
        }
        enabled.clear();
    }

    private static void disableWithin(LoadedPlugin loaded, long deadline) {
        final Thread thread = Thread.ofPlatform().daemon().name("plugin-disable-" + loaded.id()).start(() -> {
            try {
                loaded.plugin().onDisable();
            } catch (Throwable throwable) {
                LOGGER.error("Plugin failed to disable: plugin_id={}", loaded.id(), throwable);
            }
        });
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
     * Takes the admission slot for the host itself, which only the core mode does: the reserved id
     * belongs to no plugin, so nothing ever takes this registration back.
     *
     * @param provider the provider to register
     */
    void registerHostAdmission(AdmissionProvider provider) {
        admission.register(PluginDescriptors.RESERVED_ID, provider);
    }

    /**
     * Registers a channel of the host's own reserved namespace, which only the core mode does.
     *
     * @param name    the channel id, inside the reserved namespace
     * @param handler the inbound handler
     */
    void registerHostChannel(String name, PayloadHandler handler) {
        final String channelId = router.register(PluginDescriptors.RESERVED_ID, name, handler);
        LOGGER.info("Registered channel: plugin_id={} channel={}", PluginDescriptors.RESERVED_ID, channelId);
    }

    @Nullable AdmissionProvider admissionProvider() {
        return admission.provider();
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

    private void tearDown(LoadedPlugin loaded) {
        loaded.events().detach();
        loaded.scheduler().cancelAll();
        router.unregisterAll(loaded.id());
        admission.unregister(loaded.id());
        loaded.commands().unregisterAll();
        LOGGER.info("Took back the capabilities of plugin_id={}: event node, tasks, channels, admission provider, commands",
                loaded.id());
    }
}
