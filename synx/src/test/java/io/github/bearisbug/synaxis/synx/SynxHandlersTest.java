package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.instance.block.BlockManager;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one handler list the baker and a shard both register (API-M3-003, data markers). */
public class SynxHandlersTest {

    private record Named(Key getKey) implements BlockHandler {
    }

    private static final class OtherDoor implements BlockHandler {
        @Override
        public Key getKey() {
            return Key.key("synaxis:door");
        }
    }

    /** A handler the service lookup can create: public, with a public constructor. */
    public static final class ListedDoor implements BlockHandler {
        @Override
        public Key getKey() {
            return Key.key("synaxis:door");
        }
    }

    @Test
    public void everyHandlerIsRegisteredUnderItsKeyAsOneInstance() {
        final BlockHandler door = new Named(Key.key("synaxis:door"));
        final BlockHandler loot = new Named(Key.key("synaxis:loot"));
        final BlockManager blocks = new BlockManager();

        assertEquals(Set.of(door.getKey(), loot.getKey()), SynxHandlers.registerAll(blocks, List.of(door, loot)));
        assertSame(door, blocks.getHandler("synaxis:door"));
        assertSame(door, blocks.getHandler("synaxis:door"));
        assertSame(loot, blocks.getHandler("synaxis:loot"));
    }

    @Test
    public void twoHandlersWithOneKeyAreRefusedBeforeAnyIsRegistered() {
        final BlockManager blocks = new BlockManager();
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> SynxHandlers.registerAll(blocks, List.of(new Named(Key.key("synaxis:door")),
                        new Named(Key.key("synaxis:loot")), new OtherDoor())));

        final String message = refused.getMessage();
        assertTrue(message.contains("synaxis:door"), message);
        assertTrue(message.contains(Named.class.getName()), message);
        assertTrue(message.contains(OtherDoor.class.getName()), message);
        assertNull(blocks.getHandler("synaxis:door"));
        assertNull(blocks.getHandler("synaxis:loot"));
    }

    /**
     * The public overload registers the {@link BlockHandler} services, which the service lookup reads through
     * the thread's context class loader: here one over {@code classes/} that lists {@link ListedDoor}.
     */
    @Test
    public void theServicesOnTheClassPathAreRegistered(@TempDir Path temp) throws Exception {
        final Path classes = temp.resolve("classes");
        Files.writeString(Files.createDirectories(classes.resolve("META-INF/services")).resolve(BlockHandler.class.getName()),
                ListedDoor.class.getName() + "\n");
        final BlockManager blocks = new BlockManager();
        final Thread thread = Thread.currentThread();
        final ClassLoader loader = thread.getContextClassLoader();
        final Set<Key> keys;
        try (URLClassLoader listing = new URLClassLoader(new URL[]{classes.toUri().toURL()}, loader)) {
            thread.setContextClassLoader(listing);
            keys = SynxHandlers.registerAll(blocks);
        } finally {
            thread.setContextClassLoader(loader);
        }

        assertEquals(Set.of(Key.key("synaxis:door")), keys);
        assertInstanceOf(ListedDoor.class, blocks.getHandler("synaxis:door"));
    }
}
