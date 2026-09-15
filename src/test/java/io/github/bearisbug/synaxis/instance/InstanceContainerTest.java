package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.tag.Tag;
import io.github.bearisbug.synaxis.world.DimensionType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
