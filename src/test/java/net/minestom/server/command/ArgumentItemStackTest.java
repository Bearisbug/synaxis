package net.minestom.server.command;

import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.exception.ArgumentSyntaxException;
import net.minestom.server.component.DataComponents;
import net.minestom.server.item.Material;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnvTest
public class ArgumentItemStackTest {

    @Test
    public void plainMaterial(Env env) {
        var argument = ArgumentType.ItemStack("item");
        var result = assertDoesNotThrow(() -> argument.parse(null, "cooked_beef"));
        assertEquals(Material.COOKED_BEEF, result.material());
    }

    @Test
    public void componentRemovalSyntax(Env env) {
        var argument = ArgumentType.ItemStack("item");
        assertNotNull(Material.COOKED_BEEF.prototype().get(DataComponents.CONSUMABLE),
                "the test relies on cooked beef being consumable by default");

        var result = assertDoesNotThrow(() -> argument.parse(null, "cooked_beef[!consumable]"));
        assertEquals(Material.COOKED_BEEF, result.material());
        assertNull(result.get(DataComponents.CONSUMABLE));
    }

    @Test
    public void componentValueSyntax(Env env) {
        var argument = ArgumentType.ItemStack("item");
        var result = assertDoesNotThrow(() -> argument.parse(null, "stone[minecraft:damage=5]"));
        assertEquals(5, result.get(DataComponents.DAMAGE));
    }

    /**
     * Every failure has to come out as an ArgumentSyntaxException, otherwise CommandParserImpl lets it escape as a
     * bare stack trace and the player gets no feedback at all.
     */
    @Test
    public void invalidInputThrowsArgumentSyntaxException(Env env) {
        var argument = ArgumentType.ItemStack("item");
        assertThrows(ArgumentSyntaxException.class, () -> argument.parse(null, "stone[minecraft:damage=notanint]"));
        assertThrows(ArgumentSyntaxException.class, () -> argument.parse(null, "Diamond_Sword"));
        assertThrows(ArgumentSyntaxException.class, () -> argument.parse(null, "stone[not a component]"));
    }
}
