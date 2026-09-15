package io.github.bearisbug.synaxis.command.builder.arguments.minecraft;

import net.kyori.adventure.nbt.BinaryTag;
import io.github.bearisbug.synaxis.adventure.EngineAdventure;
import io.github.bearisbug.synaxis.command.ArgumentParserType;
import io.github.bearisbug.synaxis.command.CommandSender;
import io.github.bearisbug.synaxis.command.builder.arguments.Argument;
import io.github.bearisbug.synaxis.command.builder.exception.ArgumentSyntaxException;

import java.io.IOException;

/**
 * Argument used to retrieve a {@link BinaryTag} based object, can be any kind of tag like
 * {@link net.kyori.adventure.nbt.CompoundBinaryTag}, {@link net.kyori.adventure.nbt.ListBinaryTag},
 * {@link net.kyori.adventure.nbt.IntBinaryTag}, etc...
 * <p>
 * Example: {display:{Name:"{\"text\":\"Sword of Power\"}"}} or [{display:{Name:"{\"text\":\"Sword of Power\"}"}}]
 */
public class ArgumentNbtTag extends Argument<BinaryTag> {

    public static final int INVALID_NBT = 1;

    public ArgumentNbtTag(String id) {
        super(id, true);
    }

    @Override
    public BinaryTag parse(CommandSender sender, String input) throws ArgumentSyntaxException {
        try {
            return EngineAdventure.tagStringIO().asTag(input);
        } catch (IOException _) {
            throw new ArgumentSyntaxException("Invalid NBT", input, INVALID_NBT);
        }
    }

    @Override
    public ArgumentParserType parser() {
        return ArgumentParserType.NBT_TAG;
    }

    @Override
    public String toString() {
        return String.format("NBT<%s>", getId());
    }
}
