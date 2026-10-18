package io.github.bearisbug.synaxis.adventure.provider;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.serializer.ansi.ANSIComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the engine's flattener, which the plain text, legacy and ANSI serializers and every component logger get
 * through their service providers, writes a translatable component. With
 * {@code synaxis.automatic-component-translation} off, as in this test JVM: as its fallback with its arguments
 * filled in, else as its key, then its children once. With it on: as the server side translation of the component
 * without its children, whichever kind of translator gives it, which {@link EngineFlattenerProvider#translation} is
 * tested for directly.
 */
class EngineFlattenerProviderTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    /** A {@code MessageFormat} for one key and a whole component with two children for another. */
    private final Translator translator = new Translator() {
        @Override
        public Key name() {
            return Key.key("synaxis-test", "engine-flattener");
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
    void aTranslatableComponentIsItsFallbackWithItsArgumentsElseItsKey() {
        assertEquals("synaxis-test.key", PLAIN.serialize(Component.translatable("synaxis-test.key")));
        assertEquals("fb x", PLAIN.serialize(Component.translatable("synaxis-test.key", "fb %s", Component.text("x"))));
        assertEquals("fb %s %s", PLAIN.serialize(Component.translatable("synaxis-test.key", "fb %s %s",
                Component.text("x"))), "a fallback its arguments do not fill is written as it is");
        final TranslatableComponent nested = Component.translatable("synaxis-test.outer", "outer %s",
                Component.translatable("synaxis-test.inner", "inner"));
        assertEquals("outer inner", PLAIN.serialize(nested));
    }

    @Test
    void theChildrenOfATranslatableComponentAreWrittenOnceAfterIt() {
        assertEquals("synaxis-test.key tail",
                PLAIN.serialize(Component.translatable("synaxis-test.key").append(Component.text(" tail"))));
    }

    @Test
    void theLegacyAndAnsiSerializersWriteItToo() {
        final Component component = Component.translatable("synaxis-test.key", "fallback");
        assertEquals("fallback", LegacyComponentSerializer.legacySection().serialize(component));
        assertTrue(ANSIComponentSerializer.ansi().serialize(component).contains("fallback"),
                ANSIComponentSerializer.ansi().serialize(component));
    }

    @Test
    void aFormatTranslationLeavesTheChildrenToTheFlattener() {
        final TranslatableComponent answer = Component.translatable("synaxis-test.format", Component.text("2"))
                .append(Component.text(" tail"));
        assertEquals("Saved 2", PLAIN.serialize(Objects.requireNonNull(EngineFlattenerProvider.translation(answer))));
    }

    @Test
    void aComponentTranslationKeepsItsOwnLastChildren() {
        final TranslatableComponent answer = Component.translatable("synaxis-test.component")
                .append(Component.text(" a"));
        assertEquals("first second third",
                PLAIN.serialize(Objects.requireNonNull(EngineFlattenerProvider.translation(answer))));
    }

    @Test
    void aComponentTranslationWithFewerChildrenThanTheAnswerIsKeptWhole() {
        final TranslatableComponent answer = Component.translatable("synaxis-test.component")
                .append(Component.text(" a")).append(Component.text(" b")).append(Component.text(" c"))
                .append(Component.text(" d"));
        assertEquals("first second third",
                PLAIN.serialize(Objects.requireNonNull(EngineFlattenerProvider.translation(answer))));
    }

    @Test
    void noTranslationIsNull() {
        assertNull(EngineFlattenerProvider.translation(Component.translatable("synaxis-test.missing")));
    }
}
