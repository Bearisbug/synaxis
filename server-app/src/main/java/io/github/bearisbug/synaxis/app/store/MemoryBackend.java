package io.github.bearisbug.synaxis.app.store;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The player store of a process without a database ({@code synaxis.app.store=memory}, the M2 RTS
 * process): the sections of a player live as long as the process does. There is only ever one
 * process holding them, so there is no lease; the version check is kept, it is what a plugin's
 * {@code commitTogether} relies on.
 */
public final class MemoryBackend implements StoreBackend {

    private record Character(long id, long version, Map<String, StoredSection> sections) {
    }

    private final AtomicLong ids = new AtomicLong();
    // Both guarded by the monitor of characters
    private final Map<UUID, Long> byAccount = new HashMap<>();
    private final Map<Long, Character> characters = new HashMap<>();

    @Override
    public LoadOutcome load(UUID account, String name, long sessionId) {
        synchronized (characters) {
            final long id = byAccount.computeIfAbsent(account, _ -> ids.incrementAndGet());
            final Character character = characters.computeIfAbsent(id, _ -> new Character(id, 0, Map.of()));
            return new LoadOutcome.Acquired(id, character.version(), Map.copyOf(character.sections()), null, null);
        }
    }

    @Override
    public void write(long sessionId, List<Write> writes) throws FencedException {
        synchronized (characters) {
            for (Write write : writes) {
                final Character character = characters.get(write.characterId());
                if (character == null || character.version() != write.expectedVersion())
                    throw new FencedException(write.characterId());
            }
            for (Write write : writes) {
                final Character character = characters.get(write.characterId());
                final Map<String, StoredSection> sections = new HashMap<>(character.sections());
                sections.putAll(write.sections());
                characters.put(character.id(), new Character(character.id(), character.version() + 1, Map.copyOf(sections)));
            }
        }
    }

    @Override
    public void release(long characterId, long sessionId) {
        // No lease to give back
    }

    @Override
    public void releaseAccount(UUID account, long sessionId) {
        // No lease to give back
    }
}
