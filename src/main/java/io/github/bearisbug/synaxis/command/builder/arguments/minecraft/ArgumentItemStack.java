package io.github.bearisbug.synaxis.command.builder.arguments.minecraft;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import io.github.bearisbug.synaxis.adventure.EngineAdventure;
import io.github.bearisbug.synaxis.codec.Result;
import io.github.bearisbug.synaxis.codec.Transcoder;
import io.github.bearisbug.synaxis.command.ArgumentParserType;
import io.github.bearisbug.synaxis.command.CommandSender;
import io.github.bearisbug.synaxis.command.builder.arguments.Argument;
import io.github.bearisbug.synaxis.command.builder.exception.ArgumentSyntaxException;
import io.github.bearisbug.synaxis.component.DataComponent;
import io.github.bearisbug.synaxis.component.DataComponentMap;
import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.item.component.CustomData;
import io.github.bearisbug.synaxis.registry.RegistryTranscoder;

import java.io.IOException;

/**
 * Argument which can be used to retrieve an {@link ItemStack} from its material and with NBT data.
 * <p>
 * It is the same type as the one used in the /give command.
 * <p>
 * Example: diamond_sword{display:{Name:"{\"text\":\"Sword of Power\"}"}}
 */
public class ArgumentItemStack extends Argument<ItemStack> {

    public static final int NO_MATERIAL = 1;
    public static final int INVALID_NBT = 2;
    public static final int INVALID_MATERIAL = 3;
    public static final int INVALID_COMPONENT = 4;

    public ArgumentItemStack(String id) {
        super(id, true);
    }

    @Override
    public ItemStack parse(CommandSender sender, String input) throws ArgumentSyntaxException {
        return staticParse(sender.process().registries(), input);
    }

    @Override
    public ArgumentParserType parser() {
        return ArgumentParserType.ITEM_STACK;
    }

    /**
     * @deprecated use {@link Argument#parse(CommandSender, Argument)}
     */
    @SuppressWarnings("unchecked") @Deprecated
    public static ItemStack staticParse(Registries registries, String input) throws ArgumentSyntaxException {
        var reader = new StringReader(input);

        final Material material = Material.fromKey(reader.readKey(INVALID_MATERIAL));
        if (material == null)
            throw new ArgumentSyntaxException("Material is invalid", input, INVALID_MATERIAL);
        if (!reader.hasMore()) {
            return ItemStack.of(material); // Nothing else, we have our item
        }

        DataComponentMap.PatchBuilder components = DataComponentMap.patchBuilder();

        // Parse the declared components
        if (reader.peek() == '[') {
            reader.consume('[');
            final Transcoder<BinaryTag> coder = new RegistryTranscoder<>(
                    Transcoder.NBT, registries);
            do {
                // A leading '!' removes the component instead of setting it
                final boolean remove = reader.peek() == '!';
                if (remove) reader.consume('!');

                final Key componentId = reader.readKey(INVALID_COMPONENT);
                final DataComponent<?> component = DataComponent.fromKey(componentId);
                if (component == null)
                    throw new ArgumentSyntaxException("Unknown item component", input, INVALID_COMPONENT);

                if (remove) {
                    components.remove(component);
                } else {
                    if (!component.isSerialized())
                        throw new ArgumentSyntaxException("Item component cannot be set from a command", input,
                                INVALID_COMPONENT);
                    reader.consume('=');

                    final Result<Object> componentValueResult;
                    try {
                        componentValueResult = (Result<Object>) component.decode(coder, reader.readTag());
                    } catch (ArgumentSyntaxException e) {
                        throw e;
                    } catch (RuntimeException e) {
                        // A value the codec reads but the component's constructor refuses throws from the decode
                        final ArgumentSyntaxException invalid =
                                new ArgumentSyntaxException("Invalid item component value", input, INVALID_COMPONENT);
                        invalid.initCause(e);
                        throw invalid;
                    }
                    if (!(componentValueResult instanceof Result.Ok(Object componentValue)))
                        throw new ArgumentSyntaxException("Invalid item component value", input, INVALID_COMPONENT);
                    components.set((DataComponent<Object>) component, componentValue);
                }

                if (reader.peek() != ']')
                    reader.consume(',');
            } while (reader.peek() != ']');
            reader.consume(']');
        }

        // Parse the NBT
        if (reader.hasMore() && reader.peek() == '{') {
            final BinaryTag nbt = reader.readTag();
            if (!(nbt instanceof CompoundBinaryTag compound))
                throw new ArgumentSyntaxException("Item NBT must be compound", input, INVALID_NBT);

            final CompoundBinaryTag customData = CompoundBinaryTag.builder()
                    .put(components.get(DataComponents.CUSTOM_DATA, CustomData.EMPTY).nbt())
                    .put(compound)
                    .build();
            components.set(DataComponents.CUSTOM_DATA, new CustomData(customData));
        }

        if (reader.hasMore())
            throw new ArgumentSyntaxException("Unexpected remaining input", input, INVALID_NBT);

        return ItemStack.of(material, components.build());
    }

    @Override
    public String toString() {
        return String.format("ItemStack<%s>", getId());
    }

    private static class StringReader {
        private String input;
        private int index = 0;

        StringReader(String input) {
            this.input = input;
        }

        boolean hasMore() {
            return index < input.length();
        }

        char peek() {
            if (!hasMore()) {
                throw new ArgumentSyntaxException("Unexpected end of input", input, INVALID_NBT);
            }

            return input.charAt(index);
        }

        void consume(char c) {
            char next = peek();
            if (next != c) {
                throw new ArgumentSyntaxException("Expected '" + c + "', got '" + next + "'", input, INVALID_NBT);
            }
            index++;
        }

        Key readKey(int errorCode) {
            char c;
            int start = index;
            while (hasMore() && (c = peek()) != '{' && c != '[' && c != '=' && c != ']' && c != ',') {
                index++;
            }
            final String key = input.substring(start, index);
            if (!Key.parseable(key))
                throw new ArgumentSyntaxException("Invalid key '" + key + "'", input, errorCode);
            return Key.key(key);
        }

        BinaryTag readTag() {
            try {
                StringBuilder remainder = new StringBuilder();
                final BinaryTag result = EngineAdventure.tagStringIO().asTag(input.substring(index), remainder);
                this.input = remainder.toString();
                this.index = 0;

                return result;
            } catch (IOException _) {
                throw new ArgumentSyntaxException("Invalid NBT", input, INVALID_NBT);
            }
        }
    }
}
