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
 * <p>
 * It refuses what the host refuses on the caller's thread: a participant staged without sections,
 * a value staged as null, one its section cannot encode. What a test against it does not see:
 * every future it returns is complete already, its callbacks run on the calling thread, where the
 * host completes a save or a commit it does not turn away at once on a virtual thread; and nothing
 * here is frozen while a commit is written, where the host freezes the participants until the
 * future completes.
 * <p>
 * It takes what the host refuses: {@link #register} at any time and under any id, where the host
 * takes a section only inside {@code onEnable} and only under an id that starts with
 * {@code <plugin_id>:} and has more after the colon, and otherwise throws
 * {@link IllegalStateException} ({@link PlayerDataStore#register}): thrown out of
 * {@code onEnable}, it disables the plugin as the server starts, and a call after
 * {@code onEnable} throws it to the caller; and {@link #get} for any player, where the host throws
 * for a player whose data is not loaded.
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
        // The host's checks, in its order: a staged value it could not write is refused here too
        for (Map.Entry<Player, Map<DataSection<?>, Object>> entry : staged.entrySet()) {
            if (entry.getValue() == null)
                throw new IllegalArgumentException("commitTogether stages no sections for " + entry.getKey().getUsername());
            for (Map.Entry<DataSection<?>, Object> section : entry.getValue().entrySet()) {
                if (section.getValue() == null)
                    throw new IllegalArgumentException("section " + section.getKey().id() + " of "
                            + entry.getKey().getUsername() + " is staged as null");
            }
        }
        for (Player player : staged.keySet())
            if (isFrozen(player)) return CompletableFuture.completedFuture(SaveResult.UNAVAILABLE);
        for (Map.Entry<Player, Map<DataSection<?>, Object>> entry : staged.entrySet()) {
            for (Map.Entry<DataSection<?>, Object> section : entry.getValue().entrySet()) {
                try {
                    @SuppressWarnings("unchecked") final DataSection<Object> typed = (DataSection<Object>) section.getKey();
                    var _ = typed.encode(section.getValue());
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("section " + section.getKey().id() + " of "
                            + entry.getKey().getUsername() + " cannot be encoded", e);
                }
            }
        }
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
