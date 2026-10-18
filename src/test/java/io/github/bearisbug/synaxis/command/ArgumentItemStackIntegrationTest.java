package io.github.bearisbug.synaxis.command;

import io.github.bearisbug.synaxis.command.builder.arguments.ArgumentType;
import io.github.bearisbug.synaxis.command.builder.exception.ArgumentSyntaxException;
import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnvTest
public class ArgumentItemStackIntegrationTest {

    @Test
    public void plainMaterial(Env env) {
        var argument = ArgumentType.ItemStack("item");
        var result = assertDoesNotThrow(() -> argument.parse(new ServerSender(env.process()), "cooked_beef"));
        assertEquals(Material.COOKED_BEEF, result.material());
    }

    @Test
    public void componentRemovalSyntax(Env env) {
        var argument = ArgumentType.ItemStack("item");
        assertNotNull(Material.COOKED_BEEF.prototype().get(DataComponents.CONSUMABLE),
                "the test relies on cooked beef being consumable by default");

        var result = assertDoesNotThrow(() -> argument.parse(new ServerSender(env.process()), "cooked_beef[!consumable]"));
        assertEquals(Material.COOKED_BEEF, result.material());
        assertNull(result.get(DataComponents.CONSUMABLE));
    }

    @Test
    public void componentValueSyntax(Env env) {
        var argument = ArgumentType.ItemStack("item");
        var result = assertDoesNotThrow(() -> argument.parse(new ServerSender(env.process()), "stone[minecraft:damage=5]"));
        assertEquals(5, result.get(DataComponents.DAMAGE));
    }

    /**
     * Every failure has to come out as an ArgumentSyntaxException, otherwise CommandParserImpl lets it escape as a
     * bare stack trace and the player gets no feedback at all.
     */
    @Test
    public void invalidInputThrowsArgumentSyntaxException(Env env) {
        var argument = ArgumentType.ItemStack("item");
        assertThrows(ArgumentSyntaxException.class, () -> argument.parse(new ServerSender(env.process()), "stone[minecraft:damage=notanint]"));
        assertThrows(ArgumentSyntaxException.class, () -> argument.parse(new ServerSender(env.process()), "Diamond_Sword"));
        assertThrows(ArgumentSyntaxException.class, () -> argument.parse(new ServerSender(env.process()), "stone[not a component]"));
    }

    /**
     * A component that has no codec cannot be set from a command, and a value the codec reads but the component's
     * constructor refuses is invalid, instead of the exception escaping the parser.
     */
    @Test
    public void aComponentWithoutACodecOrAValueItsConstructorRefusesIsInvalid(Env env) {
        var argument = ArgumentType.ItemStack("item");
        var sender = new ServerSender(env.process());
        assertThrows(ArgumentSyntaxException.class, () -> argument.parse(sender, "stone[creative_slot_lock={}]"));
        assertThrows(ArgumentSyntaxException.class, () -> argument.parse(sender,
                "apple[consumable={on_consume_effects:[{type:\"apply_effects\",effects:[],probability:2f}]}]"));
        assertDoesNotThrow(() -> argument.parse(sender,
                "apple[consumable={on_consume_effects:[{type:\"apply_effects\",effects:[],probability:0.5f}]}]"));
    }
}
