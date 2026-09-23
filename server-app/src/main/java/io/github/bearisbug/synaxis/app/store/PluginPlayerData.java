package io.github.bearisbug.synaxis.app.store;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.plugin.DataSection;
import io.github.bearisbug.synaxis.plugin.PlayerDataStore;
import io.github.bearisbug.synaxis.plugin.PluginLifecycle;
import io.github.bearisbug.synaxis.plugin.SaveResult;
import io.github.bearisbug.synaxis.plugin.ShutdownNotice;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * What one plugin sees of the player store ({@code docs/DESIGN-PROD.md} {@code API-PROD-005},
 * §10 row level visibility): only the sections it registered itself, inside its own namespace, and
 * only while it is being enabled. Also its lifecycle hooks ({@code API-PROD-007}).
 */
public final class PluginPlayerData implements PlayerDataStore, PluginLifecycle {

    private final String pluginId;
    private final PlayerStore store;
    private final Set<String> owned = ConcurrentHashMap.newKeySet();
    private volatile boolean enabling = true;

    PluginPlayerData(String pluginId, PlayerStore store) {
        this.pluginId = pluginId;
        this.store = store;
    }

    /**
     * Ends the registration window, once the plugin's {@code onEnable} has returned.
     */
    public void enabled() {
        this.enabling = false;
    }

    @Override
    public <T> void register(DataSection<T> section) {
        if (!enabling)
            throw new IllegalStateException("plugin " + pluginId + " registers data section " + section.id()
                    + " outside onEnable");
        if (!section.id().startsWith(pluginId + ":") || section.id().length() == pluginId.length() + 1)
            throw new IllegalStateException("data section " + section.id() + " is outside the namespace of plugin "
                    + pluginId + ", it has to start with " + pluginId + ":");
        store.register(section);
        owned.add(section.id());
    }

    @Override
    public <T> T get(Player player, DataSection<T> section) {
        return store.get(player, owned(section));
    }

    @Override
    public void markDirty(Player player, DataSection<?> section) {
        store.markDirty(player, owned(section));
    }

    @Override
    public CompletableFuture<SaveResult> saveNow(Player player) {
        return store.saveNow(player);
    }

    @Override
    public CompletableFuture<SaveResult> commitTogether(Map<Player, Map<DataSection<?>, Object>> staged, Runnable apply) {
        for (Map<DataSection<?>, Object> sections : staged.values()) sections.keySet().forEach(this::owned);
        return store.commitTogether(staged, apply);
    }

    @Override
    public boolean isFrozen(Player player) {
        return store.isFrozen(player);
    }

    @Override
    public void onShutdownSave(Consumer<ShutdownNotice> hook) {
        store.addShutdownHook(pluginId, hook);
    }

    private <S extends DataSection<?>> S owned(S section) {
        if (!owned.contains(section.id()) || !store.isRegistered(section))
            throw new IllegalStateException("data section " + section.id() + " is not registered by plugin " + pluginId);
        return section;
    }
}
