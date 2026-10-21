package io.github.bearisbug.synaxis.command.builder.arguments;

import io.github.bearisbug.synaxis.command.ArgumentParserType;
import io.github.bearisbug.synaxis.command.CommandSender;
import io.github.bearisbug.synaxis.command.builder.exception.ArgumentSyntaxException;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

public class ArgumentEnum<E extends Enum<E>> extends Argument<E> {

    public final static int NOT_ENUM_VALUE_ERROR = 1;

    private final Class<E> enumClass;
    private final E[] values;
    private Format format = Format.DEFAULT;

    public ArgumentEnum(String id, Class<E> enumClass) {
        super(id);
        this.enumClass = enumClass;
        this.values = enumClass.getEnumConstants();
    }

    public ArgumentEnum<E> setFormat(Format format) {
        this.format = format;
        return this;
    }

    @Override
    public E parse(CommandSender sender, String input) throws ArgumentSyntaxException {
        for (E value : this.values) {
            if (this.format.formatter.apply(value.name()).equals(input)) {
                return value;
            }
        }
        throw new ArgumentSyntaxException("Not a " + this.enumClass.getSimpleName() + " value", input, NOT_ENUM_VALUE_ERROR);
    }

    @Override
    public @Nullable ArgumentParserType parser() {
        return null;
    }

    public List<String> entries() {
        return Arrays.stream(values).map(x -> format.formatter.apply(x.name())).toList();
    }

    @Override
    public boolean sameConfiguration(Argument<?> other) {
        return other instanceof ArgumentEnum<?> that && enumClass == that.enumClass && format == that.format;
    }

    public enum Format {
        DEFAULT(name -> name),
        LOWER_CASED(name -> name.toLowerCase(Locale.ROOT)),
        UPPER_CASED(name -> name.toUpperCase(Locale.ROOT));

        private final UnaryOperator<String> formatter;

        Format(UnaryOperator<String> formatter) {
            this.formatter = formatter;
        }
    }

    @Override
    public String toString() {
        return String.format("Enum<%s>", getId());
    }
}
