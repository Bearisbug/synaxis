package io.github.bearisbug.synaxis.entity;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.particle.Particle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MetadataHolderTest {

    @Test
    public void changesListenerCalledOnSet() {
        List<Map<Integer, Metadata.Entry<?>>> received = new ArrayList<>();
        MetadataHolder holder = new MetadataHolder(received::add);

        holder.set(MetadataDef.CUSTOM_NAME_VISIBLE, true);

        assertEquals(1, received.size());
        Map<Integer, Metadata.Entry<?>> changes = received.getFirst();
        assertEquals(1, changes.size());
        assertEquals(true, changes.get(MetadataDef.CUSTOM_NAME_VISIBLE.index()).value());
    }

    @Test
    public void changesListenerBatchedWhenNotifyDisabled() {
        List<Map<Integer, Metadata.Entry<?>>> received = new ArrayList<>();
        MetadataHolder holder = new MetadataHolder(received::add);

        holder.setNotifyAboutChanges(false);
        holder.set(MetadataDef.CUSTOM_NAME_VISIBLE, true);
        holder.set(MetadataDef.AIR_TICKS, 42);
        assertTrue(received.isEmpty(), "Listener should not be called while notification is disabled");

        holder.setNotifyAboutChanges(true);

        assertEquals(1, received.size());
        Map<Integer, Metadata.Entry<?>> changes = received.getFirst();
        assertEquals(2, changes.size());
        assertEquals(true, changes.get(MetadataDef.CUSTOM_NAME_VISIBLE.index()).value());
        assertEquals(42, changes.get(MetadataDef.AIR_TICKS.index()).value());
    }

    @Test
    public void changesListenerNotCalledWhenNothingBatched() {
        List<Map<Integer, Metadata.Entry<?>>> received = new ArrayList<>();
        MetadataHolder holder = new MetadataHolder(received::add);

        holder.setNotifyAboutChanges(false);
        holder.setNotifyAboutChanges(true);

        assertTrue(received.isEmpty());
    }

    @Test
    public void getEntriesReflectsSets() {
        MetadataHolder holder = new MetadataHolder(_ -> {
        });

        assertTrue(holder.getEntries().isEmpty(), "No entries before any set");

        // AIR_TICKS sits at a higher index than CUSTOM_NAME_VISIBLE, exercising the sparse/resized array
        holder.set(MetadataDef.CUSTOM_NAME_VISIBLE, true);
        holder.set(MetadataDef.AIR_TICKS, 42);

        Map<Integer, Metadata.Entry<?>> entries = holder.getEntries();
        assertEquals(2, entries.size(), "Only set ids are present, gaps excluded");
        assertEquals(true, entries.get(MetadataDef.CUSTOM_NAME_VISIBLE.index()).value());
        assertEquals(42, entries.get(MetadataDef.AIR_TICKS.index()).value());

        // Overwriting an id keeps the map size and reflects the new value
        holder.set(MetadataDef.AIR_TICKS, 7);
        entries = holder.getEntries();
        assertEquals(2, entries.size());
        assertEquals(7, entries.get(MetadataDef.AIR_TICKS.index()).value());
    }

    @Test
    public void getEntriesIsSnapshot() {
        MetadataHolder holder = new MetadataHolder(_ -> {
        });
        holder.set(MetadataDef.CUSTOM_NAME_VISIBLE, true);

        Map<Integer, Metadata.Entry<?>> snapshot = holder.getEntries();
        holder.set(MetadataDef.AIR_TICKS, 42);

        assertEquals(1, snapshot.size(), "Previously returned map must not see later sets");
    }

    @SuppressWarnings({"ConstantConditions", "removal"})
    @Test
    public void testNullCtor() {
        assertDoesNotThrow(() -> new MetadataHolder((Entity) null));
        assertThrows(NullPointerException.class, () -> new MetadataHolder((Consumer<Map<Integer, Metadata.Entry<?>>>) null));
    }

    @Test
    public void nestedNotifySuppressionKeepsOneBatch() {
        List<Map<Integer, Metadata.Entry<?>>> received = new ArrayList<>();
        MetadataHolder holder = new MetadataHolder(received::add);

        holder.setNotifyAboutChanges(false);
        holder.set(MetadataDef.CUSTOM_NAME_VISIBLE, true);
        holder.setNotifyAboutChanges(false); // Nested batch
        holder.set(MetadataDef.AIR_TICKS, 42);
        holder.setNotifyAboutChanges(true); // Inner batch ends, the outer one is still open
        assertTrue(received.isEmpty(), "the inner batch flushed the outer one");

        holder.set(MetadataDef.IS_SILENT, true);
        holder.setNotifyAboutChanges(true);
        assertEquals(1, received.size());
        assertEquals(3, received.getFirst().size());
    }

    @Test
    public void aNullForATypeWithoutOptIsRefusedBeforeAnythingChanges() {
        List<Map<Integer, Metadata.Entry<?>>> received = new ArrayList<>();
        MetadataHolder holder = new MetadataHolder(received::add);

        // Stored, the null would only fail on the writer thread of every viewer, disconnecting them
        assertThrows(NullPointerException.class, () -> holder.set(MetadataDef.BlockDisplay.DISPLAYED_BLOCK_STATE, null));
        assertThrows(NullPointerException.class, () -> holder.set(MetadataDef.TextDisplay.TEXT, null));
        assertTrue(holder.getEntries().isEmpty());
        assertTrue(received.isEmpty());

        holder.set(MetadataDef.CUSTOM_NAME, Component.text("name"));
        holder.set(MetadataDef.CUSTOM_NAME, null);
        assertNull(holder.get(MetadataDef.CUSTOM_NAME), "an Opt type still takes null");
        assertEquals(2, received.size());
    }

    @Test
    public void aQuaternionHasFourComponents() {
        MetadataHolder holder = new MetadataHolder(_ -> {
        });

        assertThrows(IllegalArgumentException.class, () -> holder.set(MetadataDef.Display.ROTATION_LEFT, new float[3]));
        assertTrue(holder.getEntries().isEmpty());
    }

    @Test
    public void aRotationIsCopiedOnTheWayInAndOut() {
        List<Map<Integer, Metadata.Entry<?>>> received = new ArrayList<>();
        MetadataHolder holder = new MetadataHolder(received::add);
        try {
            final float[] rotation = holder.get(MetadataDef.Display.ROTATION_LEFT);
            rotation[3] = 0.5f;
            assertArrayEquals(new float[]{0, 0, 0, 1}, MetadataDef.Display.ROTATION_LEFT.defaultValue(),
                    "the default shared by every display entity changed");

            holder.set(MetadataDef.Display.ROTATION_LEFT, rotation);
            assertEquals(1, received.size(), "the rotation changed, its viewers have to be told");
            rotation[3] = 0.25f;
            assertEquals(0.5f, holder.get(MetadataDef.Display.ROTATION_LEFT)[3], "the array set is the holder's own");

            holder.set(MetadataDef.Display.ROTATION_LEFT, rotation);
            assertEquals(2, received.size(), "an array changed in place and set again is a change");
            holder.set(MetadataDef.Display.ROTATION_LEFT, new float[]{0, 0, 0, 0.25f});
            assertEquals(2, received.size(), "an equal array is no change");
        } finally {
            // A failing run must not leave the default shared by every display entity changed
            MetadataDef.Display.ROTATION_LEFT.defaultValue()[3] = 1;
        }
    }

    @Test
    public void aParticleListIsCopiedOnTheWayIn() {
        MetadataHolder holder = new MetadataHolder(_ -> {
        });
        final List<Particle> particles = new ArrayList<>(List.of(Particle.FLAME));

        holder.set(MetadataDef.LivingEntity.POTION_EFFECT_PARTICLES, particles);
        particles.add(Particle.HEART);

        assertEquals(List.of(Particle.FLAME), holder.get(MetadataDef.LivingEntity.POTION_EFFECT_PARTICLES));
    }
}
