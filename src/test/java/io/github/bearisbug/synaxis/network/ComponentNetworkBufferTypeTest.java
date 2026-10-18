package io.github.bearisbug.synaxis.network;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntBinaryTag;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.object.ObjectContents;
import io.github.bearisbug.synaxis.adventure.ComponentCodecs;
import io.github.bearisbug.synaxis.codec.Transcoder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.COMPONENT;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.NBT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ComponentNetworkBufferTypeTest {
    @Test
    void empty() {
        var comp = Component.empty();
        assertWriteReadEquality(comp);
    }

    @Test
    void text() {
        var comp = Component.text("Hello, world!");
        assertWriteReadEquality(comp);
    }

    @Test
    void textChildren() {
        var comp = Component.text("Hello, world!").children(List.of(
                Component.text("child 1"),
                Component.text("child 2")
        ));
        assertWriteReadEquality(comp);
    }

    @Test
    void translatable() {
        var comp = Component.translatable("a.b.c", "I am fallback", Component.text("arg1"), Component.text("arg2"));
        assertWriteReadEquality(comp);
    }

    @Test
    void score() {
        var comp = Component.score("test123", "obj");
        assertWriteReadEquality(comp);
    }

    @Test
    void selector() {
        var comp = Component.selector("@a", Component.text(", "));
        assertWriteReadEquality(comp);
    }

    @Test
    void keybind() {
        var comp = Component.keybind("key.jump");
        assertWriteReadEquality(comp);
    }

    @Test
    void textModifiedUtf8() {
        var comp = Component.text("abc\0\0def");
        assertWriteReadEquality(comp);
    }

    @Test
    void hoverAction() {
        var comp = Component.text("hello").hoverEvent(Component.text("world"));
        assertWriteReadEquality(comp);
    }

    @Test
    void testObjectComponentHeadString() {
        var comp = Component.object(ObjectContents.playerHead("Hello"));
        assertWriteReadEquality(comp);
    }

    @Test
    void testObjectComponentHeadUUID() {
        var comp = Component.object(ObjectContents.playerHead(UUID.randomUUID()));
        assertWriteReadEquality(comp);
    }

    @Test
    void objectComponentHeadTexture() {
        var comp = Component.object(ObjectContents.playerHead()
                .texture(Key.key("red"))
                .build());

        final CompoundBinaryTag player = write(comp).getCompound("player");
        assertEquals("red", player.getString("texture"));
        assertFalse(player.contains("body"));
        assertWriteReadEquality(comp);
    }

    @Test
    void objectComponentFallback() {
        var comp = Component.object()
                .contents(ObjectContents.sprite(Key.key("missing")))
                .fallback(Component.text("Missing"))
                .build();

        final CompoundBinaryTag written = write(comp);
        assertInstanceOf(CompoundBinaryTag.class, written.get("fallback"));
        assertEquals(comp, ComponentCodecs.COMPONENT.decode(Transcoder.NBT, written).orElseThrow());
    }

    /**
     * Written on the write thread of every player a message goes to, so a payload it cannot write drops all of them.
     */
    @Test
    void customClickEventWithoutPayload() {
        var comp = Component.text("hello").clickEvent(ClickEvent.custom(Key.key("hello:world")));

        assertFalse(write(comp).getCompound("click_event").keySet().contains("payload"));
        assertWriteReadEquality(comp);
    }

    @Test
    void customClickEventWithATagThatIsNotACompound() {
        var comp = Component.text("hello").clickEvent(ClickEvent.custom(
                Key.key("hello:world"), BinaryTagHolder.binaryTagHolder("5")));

        assertEquals(IntBinaryTag.intBinaryTag(5), write(comp).getCompound("click_event").get("payload"));
    }

    /** A component the writer cannot encode is refused as an argument, the exception a caller of COMPONENT expects. */
    @Test
    void anNbtComponentIsRefusedAsAnArgument() {
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> write(Component.storageNBT("path", Key.key("synaxis:storage"))));
        assertTrue(refused.getMessage().contains("NBTComponent"), refused.getMessage());
    }

    /** A binary tag string takes at most 65,535 bytes of modified UTF-8, a longer text is refused as an argument. */
    @Test
    void aTextOverTheBinaryTagStringLimitIsRefusedAsAnArgument() {
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> write(Component.text("a".repeat(70_000))));
        assertTrue(refused.getMessage().contains("70000"), refused.getMessage());
    }

    private static void assertWriteReadEquality(Component comp) {
        final CompoundBinaryTag written = write(comp);
        final Component actual = ComponentCodecs.COMPONENT.decode(Transcoder.NBT, written).orElseThrow();
        assertEquals(comp, actual);
    }

    private static CompoundBinaryTag write(Component comp) {
        var array = NetworkBuffer.makeArray(buffer -> buffer.write(COMPONENT, comp));
        var buffer = NetworkBuffer.wrap(array, 0, array.length);
        return assertInstanceOf(CompoundBinaryTag.class, buffer.read(NBT));
    }
}
