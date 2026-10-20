package io.github.bearisbug.synaxis.adventure;

import com.google.gson.JsonElement;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntBinaryTag;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.object.ObjectContents;
import io.github.bearisbug.synaxis.adventure.EngineAdventure;
import io.github.bearisbug.synaxis.codec.Result;
import io.github.bearisbug.synaxis.codec.Transcoder;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ComponentCodecsTest {

    /**
     * A Decoder must report a failure as Result.Error. An unknown action name arrives straight from the client, so
     * letting an unchecked exception out breaks every caller that expects a Result.
     */
    @Test
    void unknownClickEventActionIsAnError() throws Exception {
        var input = EngineAdventure.tagStringIO().asTag("{text:\"a\",click_event:{action:\"bogus\"}}");
        assertInstanceOf(Result.Error.class, ComponentCodecs.COMPONENT.decode(Transcoder.NBT, input));
    }

    @Test
    void unknownHoverEventActionIsAnError() throws Exception {
        var input = EngineAdventure.tagStringIO().asTag("{text:\"a\",hover_event:{action:\"bogus\"}}");
        assertInstanceOf(Result.Error.class, ComponentCodecs.COMPONENT.decode(Transcoder.NBT, input));
    }

    @Test
    void readExpandFromStringInList() throws Exception {
        var input = EngineAdventure.tagStringIO().asTag("{extra:[{color:\"red\",text:\"Hello\"},\" World\"],text:\"\"}");
        var actual = ComponentCodecs.COMPONENT.decode(Transcoder.NBT, input).orElseThrow();
        var expected = Component.text()
                .append(Component.text("Hello", NamedTextColor.RED))
                .append(Component.text(" World"))
                .build();
        assertEquals(expected, actual);
    }

    @Test
    void writeFlattenToInList() throws IOException {
        var component = Component.text()
                .append(Component.text("Hello", NamedTextColor.RED))
                .append(Component.text(" World"))
                .build();
        var nbt = ComponentCodecs.COMPONENT.encode(Transcoder.NBT, component).orElseThrow();
        assertEquals("{extra:[{color:\"red\",text:\"Hello\"},\" World\"],text:\"\"}", EngineAdventure.tagStringIO().asString(nbt));
    }

    @Test
    void writeCustomClickEvent() throws IOException {
        var component = Component.text("Click me!").clickEvent(ClickEvent.custom(
                Key.key("hello:world"), EngineAdventure.wrapNbt(IntBinaryTag.intBinaryTag(55))));
        var nbt = ComponentCodecs.COMPONENT.encode(Transcoder.NBT, component).orElseThrow();
        assertEquals("{click_event:{payload:55,action:\"custom\",id:\"hello:world\"},text:\"Click me!\"}",
                EngineAdventure.tagStringIO().asString(nbt));
    }

    @Test
    void readCustomClickEvent() throws IOException {
        var input = EngineAdventure.tagStringIO().asTag("{click_event:{payload:55,action:\"custom\",id:\"hello:world\"},text:\"Click me!\"}");
        var actual = ComponentCodecs.COMPONENT.decode(Transcoder.NBT, input).orElseThrow();
        var expected = Component.text("Click me!").clickEvent(ClickEvent.custom(
                Key.key("hello:world"), EngineAdventure.wrapNbt(IntBinaryTag.intBinaryTag(55))));
        assertEquals(expected, actual);
    }

    /**
     * The payload of a custom click event is optional in 26.2, and Adventure's {@code ClickEvent.custom(Key)} leaves
     * it out.
     */
    @Test
    void customClickEventWithoutPayloadRoundTrips() {
        var component = Component.text("Click me!").clickEvent(ClickEvent.custom(Key.key("hello:world")));

        final BinaryTag nbt = ComponentCodecs.COMPONENT.encode(Transcoder.NBT, component).orElseThrow();
        assertFalse(((CompoundBinaryTag) nbt).getCompound("click_event").keySet().contains("payload"));
        assertEquals(component, ComponentCodecs.COMPONENT.decode(Transcoder.NBT, nbt).orElseThrow());

        final JsonElement json = ComponentCodecs.COMPONENT.encode(Transcoder.JSON, component).orElseThrow();
        assertFalse(json.getAsJsonObject().getAsJsonObject("click_event").has("payload"));
        assertEquals(component, ComponentCodecs.COMPONENT.decode(Transcoder.JSON, json).orElseThrow());
    }

    /**
     * 26.2 takes any tag as the payload, not only a compound.
     */
    @Test
    void customClickEventPayloadCanBeAnyTag() {
        var component = Component.text("Click me!").clickEvent(ClickEvent.custom(
                Key.key("hello:world"), BinaryTagHolder.binaryTagHolder("5")));

        final BinaryTag nbt = ComponentCodecs.COMPONENT.encode(Transcoder.NBT, component).orElseThrow();
        assertEquals(IntBinaryTag.intBinaryTag(5), ((CompoundBinaryTag) nbt).getCompound("click_event").get("payload"));
    }

    @Test
    void unwrapNbtNamesTheStringItCannotRead() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> EngineAdventure.unwrapNbt(BinaryTagHolder.binaryTagHolder("{")));
        assertEquals("BinaryTagHolder does not hold valid SNBT: {", error.getMessage());
    }

    /** Without a type the content is guessed from its fields, in a fixed order. */
    @Test
    void readWithoutTypeGuessesTheContent() throws Exception {
        assertEquals(Component.translatable("a"), ComponentCodecs.COMPONENT.decode(Transcoder.NBT,
                EngineAdventure.tagStringIO().asTag("{translate:\"a\"}")).orElseThrow());
        assertEquals(Component.keybind("k"), ComponentCodecs.COMPONENT.decode(Transcoder.NBT,
                EngineAdventure.tagStringIO().asTag("{keybind:\"k\"}")).orElseThrow());
        final Result<Component> unknown = ComponentCodecs.COMPONENT.decode(Transcoder.NBT,
                EngineAdventure.tagStringIO().asTag("{color:\"red\"}"));
        assertEquals("Unable to determine component type", assertInstanceOf(Result.Error.class, unknown).message());
    }

    @Test
    void translatableArgumentsRoundTrip() {
        var component = Component.translatable("a", Component.text("b"), Component.text("c", NamedTextColor.RED));
        var nbt = ComponentCodecs.COMPONENT.encode(Transcoder.NBT, component).orElseThrow();
        assertEquals(component, ComponentCodecs.COMPONENT.decode(Transcoder.NBT, nbt).orElseThrow());
    }

    @Test
    void readShowEntityWithoutName() {
        UUID uuid = UUID.randomUUID();

        var input = CompoundBinaryTag.builder()
                .putString("text", "hover")
                .put("hover_event", CompoundBinaryTag.builder()
                        .putString("action", "show_entity")
                        .putString("id", "minecraft:player")
                        .putString("uuid", uuid.toString())
                        .build())
                .build();

        var actual = ComponentCodecs.COMPONENT.decode(Transcoder.NBT, input).orElseThrow();
        var expected = Component.text("hover")
                .hoverEvent(HoverEvent.showEntity(
                        Key.key("minecraft:player"),
                        uuid,
                        null
                ));

        assertEquals(expected, actual);
    }

    @Test
    void readObjectFallback() {
        var input = CompoundBinaryTag.builder()
                .putString("type", "object")
                .putString("sprite", "missing")
                .putString("fallback", "Missing")
                .build();

        var actual = ComponentCodecs.COMPONENT.decode(Transcoder.NBT, input).orElseThrow();
        var expected = Component.object()
                .contents(ObjectContents.sprite(Key.key("missing")))
                .fallback(Component.text("Missing"))
                .build();

        assertEquals(expected, actual);
    }

    @Test
    void writeObjectFallback() {
        var component = Component.object()
                .contents(ObjectContents.sprite(Key.key("missing")))
                .fallback(Component.text("Missing"))
                .build();

        var nbt = ComponentCodecs.COMPONENT.encode(Transcoder.NBT, component).orElseThrow();
        var actual = ComponentCodecs.COMPONENT.decode(Transcoder.NBT, nbt).orElseThrow();

        assertEquals(component, actual);
    }

    @Test
    void writeObjectPlayerHeadProfileAndTexture() {
        final UUID uuid = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");
        var component = Component.object(ObjectContents.playerHead()
                .name("Steve")
                .id(uuid)
                .profileProperty(new GameProfile.Property("test", "value", null))
                .texture(Key.key("red"))
                .build());

        final var nbt = ComponentCodecs.COMPONENT.encode(Transcoder.NBT, component).orElseThrow();
        final var player = ((CompoundBinaryTag) nbt).getCompound("player");
        assertEquals("Steve", player.getString("name"));
        assertEquals("minecraft:red", player.getString("texture"));
        assertEquals(component, ComponentCodecs.COMPONENT.decode(Transcoder.NBT, nbt).orElseThrow());
    }
}
