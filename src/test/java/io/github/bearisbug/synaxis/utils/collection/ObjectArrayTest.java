package io.github.bearisbug.synaxis.utils.collection;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ObjectArrayTest {

    @Test
    public void objectArray() {
        ObjectArray<String> array = ObjectArray.singleThread();

        array.set(50, "Hey");
        assertEquals("Hey", array.get(50));
        assertNull(array.get(49));
        assertNull(array.get(51));

        array.set(0, "Hey2");
        assertEquals("Hey2", array.get(0));
        assertEquals("Hey", array.get(50));

        array.set(250, "Hey3");
        assertEquals("Hey3", array.get(250));
        assertEquals("Hey2", array.get(0));
        assertEquals("Hey", array.get(50));

        assertNull(array.get(49));
        assertNull(array.get(251));
        assertNull(array.get(Integer.MAX_VALUE));
    }

    @Test
    public void toListEndsAtTheHighestElement() {
        ObjectArray<String> array = ObjectArray.singleThread(16);
        assertEquals(List.of(), array.toList());

        array.set(0, "Hey");
        array.set(1, "Hey2");
        array.set(3, "Hey4");
        array.set(2, "Hey3");
        assertEquals(List.of("Hey", "Hey2", "Hey3", "Hey4"), array.toList());

        // Removing the last elements backtracks past the ones already null
        array.remove(2);
        array.remove(3);
        assertEquals(List.of("Hey", "Hey2"), array.toList());

        array.set(1, null);
        assertEquals(List.of("Hey"), array.toList());

        array.remove(0);
        assertEquals(List.of(), array.toList());
    }
}
