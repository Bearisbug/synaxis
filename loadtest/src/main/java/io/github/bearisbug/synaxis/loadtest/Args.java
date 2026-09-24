package io.github.bearisbug.synaxis.loadtest;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@code --name value} options and the positional arguments between them. */
final class Args {

    private final Map<String, String> options = new HashMap<>();
    private final Set<String> read = new HashSet<>();
    final List<String> positional = new ArrayList<>();

    Args(List<String> arguments) {
        for (int index = 0; index < arguments.size(); index++) {
            final String argument = arguments.get(index);
            if (!argument.startsWith("--")) {
                positional.add(argument);
                continue;
            }
            if (index + 1 >= arguments.size()) throw new IllegalArgumentException(argument + " needs a value");
            options.put(argument.substring(2), arguments.get(++index));
        }
    }

    String string(String name, String fallback) {
        read.add(name);
        return options.getOrDefault(name, fallback);
    }

    private @Nullable String optional(String name) {
        read.add(name);
        return options.get(name);
    }

    String required(String name) {
        read.add(name);
        final String value = options.get(name);
        if (value == null) throw new IllegalArgumentException("--" + name + " is required");
        return value;
    }

    int integer(String name, int fallback) {
        final String value = optional(name);
        return value == null ? fallback : Integer.parseInt(value.strip());
    }

    double number(String name, double fallback) {
        final String value = optional(name);
        return value == null ? fallback : Double.parseDouble(value.strip());
    }

    boolean onOff(String name, boolean fallback) {
        final String value = optional(name);
        if (value == null) return fallback;
        return switch (value.strip()) {
            case "on" -> true;
            case "off" -> false;
            default -> throw new IllegalArgumentException("--" + name + " is on or off, not " + value);
        };
    }

    /**
     * A duration in seconds: {@code 600s}, {@code 30m}, {@code 1h} or a bare number of seconds.
     */
    double seconds(String name, double fallback) {
        final String value = optional(name);
        return value == null ? fallback : seconds(value);
    }

    static double seconds(String value) {
        final String text = value.strip();
        final char unit = text.charAt(text.length() - 1);
        final double scale = switch (unit) {
            case 's' -> 1;
            case 'm' -> 60;
            case 'h' -> 3600;
            default -> 0;
        };
        if (scale == 0) return Double.parseDouble(text);
        return Double.parseDouble(text.substring(0, text.length() - 1)) * scale;
    }

    /** A rate per second: {@code 100/s} or a bare number. */
    double rate(String name, double fallback) {
        final String value = optional(name);
        if (value == null) return fallback;
        final String text = value.strip();
        return Double.parseDouble(text.endsWith("/s") ? text.substring(0, text.length() - 2) : text);
    }

    /** Fails on an option nobody read: a misspelt one would otherwise be ignored silently. */
    void requireAllRead() {
        final Set<String> unknown = new HashSet<>(options.keySet());
        unknown.removeAll(read);
        if (!unknown.isEmpty()) throw new IllegalArgumentException("unknown option(s): --" + String.join(", --", unknown));
    }
}
