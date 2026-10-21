package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.tag.Tag;
import io.github.bearisbug.synaxis.world.DimensionType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class InstanceContainerTest {

    @Test
    public void copyPreservesTag() {
        var tag = Tag.String("test");
        var instance = new InstanceContainer(ServerProcess.of(), UUID.randomUUID(), DimensionType.OVERWORLD);
        instance.setTag(tag, "123");

        var copyInstance = instance.copy();
        var result = copyInstance.getTag(tag);
        assertEquals("123", result);
    }

    // Refused when set, not on the next chunk load on a virtual thread
    @Test
    public void setChunkSupplierRejectsNull() {
        var instance = new InstanceContainer(ServerProcess.of(), UUID.randomUUID(), DimensionType.OVERWORLD);
        assertThrows(NullPointerException.class, () -> instance.setChunkSupplier(null));
    }
}
