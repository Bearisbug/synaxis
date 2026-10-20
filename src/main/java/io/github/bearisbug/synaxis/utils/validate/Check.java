package io.github.bearisbug.synaxis.utils.validate;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.text.MessageFormat;
import java.util.Objects;

/**
 * Convenient class to check for common exceptions.
 *
 * <p>The overloads that take arguments build the message with {@link MessageFormat}: {@code reason} is a pattern
 * where {@code {0}} stands for the first argument and a literal apostrophe is written {@code ''}. Numbers are
 * formatted for the default locale, with grouping separators (1024 becomes "1,024" in English); write
 * {@code {0,number,#}} or pass {@code String.valueOf(n)} to avoid them. Never build the pattern from external input:
 * a brace in it breaks the format. The overloads without arguments use {@code reason} as it is.</p>
 */
public final class Check {

    private Check() {

    }

    @Deprecated(forRemoval = true) // Use Objects.requireNonNull instead. (Has much better IDE support)
    @Contract("null, _ -> fail")
    public static void notNull(@Nullable Object object, String reason) {
        if (Objects.isNull(object)) {
            throw new NullPointerException(reason);
        }
    }

    /**
     * Throws a {@link NullPointerException} if {@code object} is null.
     *
     * @param object    the object
     * @param reason    the {@link MessageFormat} pattern of the message, see the class documentation
     * @param arguments the arguments of the pattern
     */
    @Contract("null, _, _ -> fail")
    public static void notNull(@Nullable Object object, String reason, Object... arguments) {
        if (Objects.isNull(object)) {
            throw new NullPointerException(MessageFormat.format(reason, arguments));
        }
    }

    @Contract("true, _ -> fail")
    public static void argCondition(boolean condition, String reason) {
        if (condition) {
            throw new IllegalArgumentException(reason);
        }
    }

    /**
     * Throws an {@link IllegalArgumentException} if {@code condition} is true.
     *
     * @param condition the condition
     * @param reason    the {@link MessageFormat} pattern of the message, see the class documentation
     * @param arguments the arguments of the pattern
     */
    @Contract("true, _, _ -> fail")
    public static void argCondition(boolean condition, String reason, Object... arguments) {
        if (condition) {
            throw new IllegalArgumentException(MessageFormat.format(reason, arguments));
        }
    }

    @Deprecated(forRemoval = true) // Just throw instead, as javac control flow is opaque to calling this function.
    @Contract("_ -> fail")
    public static void fail(String reason) {
        throw new IllegalArgumentException(reason);
    }

    /**
     * Throws an {@link IllegalArgumentException}.
     *
     * @param reason    the {@link MessageFormat} pattern of the message, see the class documentation
     * @param arguments the arguments of the pattern
     */
    @Contract("_, _ -> fail")
    public static void fail(String reason, Object... arguments) {
        throw new IllegalArgumentException(MessageFormat.format(reason, arguments));
    }

    @Contract("true, _ -> fail")
    public static void stateCondition(boolean condition, String reason) {
        if (condition) {
            throw new IllegalStateException(reason);
        }
    }

    /**
     * Throws an {@link IllegalStateException} if {@code condition} is true.
     *
     * @param condition the condition
     * @param reason    the {@link MessageFormat} pattern of the message, see the class documentation
     * @param arguments the arguments of the pattern
     */
    @Contract("true, _, _ -> fail")
    public static void stateCondition(boolean condition, String reason, Object... arguments) {
        if (condition) {
            throw new IllegalStateException(MessageFormat.format(reason, arguments));
        }
    }

    @Contract("false, _ -> fail")
    public static void isTrue(boolean condition, String reason) {
        if (!condition) {
            throw new IllegalStateException(reason);
        }
    }

    @Contract("false, _, _ -> fail")
    public static void isTrue(boolean condition, String reason, Object... arguments) {
        if (!condition) {
            throw new IllegalStateException(MessageFormat.format(reason, arguments));
        }
    }
}
