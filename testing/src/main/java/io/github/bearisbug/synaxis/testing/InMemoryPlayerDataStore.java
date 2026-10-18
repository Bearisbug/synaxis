package io.github.bearisbug.synaxis.testing;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.plugin.DataSection;
import io.github.bearisbug.synaxis.plugin.PlayerDataStore;
import io.github.bearisbug.synaxis.plugin.SaveResult;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link PlayerDataStore} for a plugin's own tests ({@code docs/DESIGN-PROD.md}
 * {@code API-PROD-005}): no database, every save succeeds, and a commit applies at once on the
 * calling thread. The host's store applies on the serial part of the next tick instead, so a
 * participant leaving in between is a case only a test against the host covers. A test freezes a
 * player with {@link #freeze} to take the path a plugin must take while the real store refuses
 * changes of ownership.
 */
public final class InMemoryPlayerDataStore implements PlayerDataStore {

    private final Map<String, DataSection<?>> sections = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Object>> values = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> dirty = new ConcurrentHashMap<>();
    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();

    @Override
    public <T> void register(DataSection<T> section) {
        if (sections.putIfAbsent(section.id(), section) != null)
            throw new IllegalStateException("data section " + section.id() + " is already registered");
    }

    @Override
    public <T> T get(Player player, DataSection<T> section) {
        if (sections.get(section.id()) != section)
            throw new IllegalStateException("data section " + section.id() + " is not registered");
        @SuppressWarnings("unchecked") final T value = (T) values
                .computeIfAbsent(player.getUuid(), _ -> new ConcurrentHashMap<>())
                .computeIfAbsent(section.id(), _ -> section.createDefault());
        return value;
    }

    @Override
    public void markDirty(Player player, DataSection<?> section) {
        dirty.computeIfAbsent(player.getUuid(), _ -> ConcurrentHashMap.newKeySet()).add(section.id());
    }

    /**
     * The sections of a player marked dirty since its last save, for a test to assert on.
     *
     * @param player the player
     * @return the section ids
     */
    public Set<String> dirty(Player player) {
        return Set.copyOf(dirty.getOrDefault(player.getUuid(), Set.of()));
    }

    @Override
    public CompletableFuture<SaveResult> saveNow(Player player) {
        if (isFrozen(player)) return CompletableFuture.completedFuture(SaveResult.UNAVAILABLE);
        dirty.remove(player.getUuid());
        return CompletableFuture.completedFuture(SaveResult.SAVED);
    }

    @Override
    public CompletableFuture<SaveResult> commitTogether(Map<Player, Map<DataSection<?>, Object>> staged, Runnable apply) {
        for (Player player : staged.keySet())
            if (isFrozen(player)) return CompletableFuture.completedFuture(SaveResult.UNAVAILABLE);
        for (Map.Entry<Player, Map<DataSection<?>, Object>> entry : staged.entrySet()) {
            final Map<String, Object> playerValues = values.computeIfAbsent(entry.getKey().getUuid(), _ -> new ConcurrentHashMap<>());
            for (Map.Entry<DataSection<?>, Object> section : entry.getValue().entrySet())
                playerValues.put(section.getKey().id(), section.getValue());
        }
        apply.run();
        return CompletableFuture.completedFuture(SaveResult.SAVED);
    }

    /**
     * Freezes or thaws a player, as the real store does during a shutdown save or a database outage.
     *
     * @param player the player
     * @param frozen whether it is frozen
     */
    public void freeze(Player player, boolean frozen) {
        if (frozen) this.frozen.add(player.getUuid());
        else this.frozen.remove(player.getUuid());
    }

    @Override
    public boolean isFrozen(Player player) {
        return frozen.contains(player.getUuid());
    }
}
