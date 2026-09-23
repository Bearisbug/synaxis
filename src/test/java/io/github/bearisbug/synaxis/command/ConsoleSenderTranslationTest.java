package io.github.bearisbug.synaxis.command;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;
import net.kyori.adventure.translation.Translator;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What the console writes for a translatable answer with {@code synaxis.automatic-component-translation}
 * on: the translation without the answer's children, whichever kind of translator gives it.
 */
class ConsoleSenderTranslationTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    /** A {@code MessageFormat} for one key and a whole component with two children for another. */
    private final Translator translator = new Translator() {
        @Override
        public Key name() {
            return Key.key("synaxis-test", "console-sender");
        }

        @Override
        public @Nullable MessageFormat translate(String key, Locale locale) {
            return key.equals("synaxis-test.format") ? new MessageFormat("Saved {0}", Locale.ROOT) : null;
        }

        @Override
        public @Nullable Component translate(TranslatableComponent component, Locale locale) {
            if (!component.key().equals("synaxis-test.component")) return null;
            return Component.text("first").append(Component.text(" second")).append(Component.text(" third"));
        }
    };

    @BeforeEach
    void addTranslator() {
        GlobalTranslator.translator().addSource(translator);
    }

    @AfterEach
    void removeTranslator() {
        GlobalTranslator.translator().removeSource(translator);
    }

    @Test
    void aFormatTranslationLeavesTheChildrenToTheFlattener() {
        final TranslatableComponent answer = Component.translatable("synaxis-test.format", Component.text("2"))
                .append(Component.text(" tail"));
        assertEquals("Saved 2", PLAIN.serialize(Objects.requireNonNull(ConsoleSender.translation(answer))));
    }

    @Test
    void aComponentTranslationKeepsItsOwnLastChildren() {
        final TranslatableComponent answer = Component.translatable("synaxis-test.component")
                .append(Component.text(" a"));
        assertEquals("first second third", PLAIN.serialize(Objects.requireNonNull(ConsoleSender.translation(answer))));
    }

    @Test
    void aComponentTranslationWithFewerChildrenThanTheAnswerIsKeptWhole() {
        final TranslatableComponent answer = Component.translatable("synaxis-test.component")
                .append(Component.text(" a")).append(Component.text(" b")).append(Component.text(" c"))
                .append(Component.text(" d"));
        assertEquals("first second third", PLAIN.serialize(Objects.requireNonNull(ConsoleSender.translation(answer))));
    }

    @Test
    void noTranslationIsNull() {
        assertNull(ConsoleSender.translation(Component.translatable("synaxis-test.missing")));
    }
}
