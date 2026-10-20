package io.github.bearisbug.synaxis.command.builder.arguments.minecraft.registry;

import io.github.bearisbug.synaxis.command.CommandSender;
import io.github.bearisbug.synaxis.command.builder.arguments.Argument;
import io.github.bearisbug.synaxis.command.builder.exception.ArgumentSyntaxException;
import org.jetbrains.annotations.Nullable;

public abstract class ArgumentRegistry<T> extends Argument<T> {

    public static final int INVALID_NAME = -2;

    public ArgumentRegistry(String id) {
        super(id);
    }

    /**
     * Returns the entry of the registry this key names, or null if the key is unknown, which the parser reports as
     * {@link #INVALID_NAME}. Do not throw: the parser only catches {@link ArgumentSyntaxException}.
     *
     * @param value the key as typed
     * @return the entry, null if there is none
     */
    public abstract @Nullable T getRegistry(String value);

    @Override
    public T parse(CommandSender sender, String input) throws ArgumentSyntaxException {
        final T registryValue = getRegistry(input);
        if (registryValue == null)
            throw new ArgumentSyntaxException("Registry value is invalid", input, INVALID_NAME);

        return registryValue;
    }
}
