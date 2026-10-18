package io.github.bearisbug.synaxis.item.component;

import io.github.bearisbug.synaxis.command.builder.arguments.minecraft.ArgumentItemStack;
import io.github.bearisbug.synaxis.command.builder.exception.ArgumentSyntaxException;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.testing.RegistriesTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.Collections;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A value an item component refuses decodes as an error rather than throwing out of the codec, so the item argument
 * of a command reports a syntax error instead of failing the command with a stack trace.
 */
@RegistriesTest
public class ItemComponentDecodingRegistriesTest {

    @Test
    @SuppressWarnings("deprecation")
    void aRefusedComponentValueInAnItemArgumentIsASyntaxError(Registries registries) {
        assertAll(Stream.of(
                "stone[use_cooldown={seconds:1f,cooldown_group:\"Fire Ball\"}]",
                "stone[equippable={slot:\"head\",asset_id:\"My Armor\"}]",
                "stone[equippable={slot:\"head\",camera_overlay:\"misc/Pumpkin Blur\"}]",
                "stone[written_book_content={title:{raw:\"" + "x".repeat(33) + "\"},author:\"a\"}]",
                "stone[writable_book_content={pages:[" + String.join(",", Collections.nCopies(101, "{raw:\"p\"}")) + "]}]",
                "stone[item_model=\"Bad Model\"]",
                "stone[tooltip_style=\"my style\"]",
                "stone[note_block_sound=\"Note\"]"
        ).map(input -> (Executable) () -> assertThrows(ArgumentSyntaxException.class,
                () -> ArgumentItemStack.staticParse(registries, input), input)));
        assertDoesNotThrow(() -> ArgumentItemStack.staticParse(registries,
                "stone[use_cooldown={seconds:1f,cooldown_group:\"synaxis:fire_ball\"},item_model=\"synaxis:unit/archer\","
                        + "written_book_content={title:{raw:\"" + "x".repeat(32) + "\"},author:\"a\"}]"));
    }
}
