package io.github.bearisbug.synaxis.game.client.play;

import io.github.bearisbug.synaxis.game.protocol.play.EncodedComponent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The client half of the first step of {@code docs/DESIGN-PLAY.md} §26 S1: the vanilla component stream codec
 * reads the bytes the host's {@code ComponentBytes} writes. The three files under {@code play-vectors/component/}
 * are the ones the server's {@code PlayComponentVectorTest} holds {@code ComponentBytes} to, and the components
 * the fixture of the {@code synaxis:play} gametest sends; the build points {@code synaxis.play-vectors.dir} at that directory.
 * <p>
 * Should the codec not read them, the fallback of {@code API-PLAY-002} applies: the host writes the engine's JSON
 * encoding instead, which changes {@code ComponentBytes} and {@link PlayComponents} and nothing else.
 */
class PlayComponentDecodeTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void theTitleIsAKeyWithoutArguments() throws IOException {
        final Component title = decode("title.bin");

        final TranslatableContents contents = assertInstanceOf(TranslatableContents.class, title.getContents());
        assertEquals("key.category.synaxis.play", contents.getKey());
        assertEquals(List.of(), arguments(contents));
        assertNull(contents.getFallback());
        assertNull(title.getStyle().getColor());
    }

    @Test
    void theHintKeepsItsKeyItsArgumentItsColourAndItsFallback() throws IOException {
        final Component hint = decode("hint.bin");

        final TranslatableContents contents = assertInstanceOf(TranslatableContents.class, hint.getContents());
        assertEquals("synaxis.action.cooldown", contents.getKey());
        assertEquals(List.of("1"), arguments(contents));
        assertEquals("The fallback of synaxis.action.cooldown: %s", contents.getFallback());
        assertEquals(0xFFAA00, colour(hint));
    }

    @Test
    void aKeyTheLanguageFilesLackKeepsItsFallbackAndItsColour() throws IOException {
        final Component missing = decode("missing.bin");

        final TranslatableContents contents = assertInstanceOf(TranslatableContents.class, missing.getContents());
        assertEquals("synaxis_test.missing", contents.getKey());
        assertEquals(List.of(), arguments(contents));
        assertEquals("Shown from the fallback", contents.getFallback());
        assertEquals(0x55FF55, colour(missing));
    }

    @Test
    void anEmptyComponentIsEmptyTextAndNeverReachesTheCodec() {
        // No registries at all: a decode would fail on the null the moment the codec read through them
        assertEquals(Component.empty(), PlayComponents.decode(EncodedComponent.NONE, null));
    }

    @Test
    void bytesTheCodecCannotReadAreRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> PlayComponents.decode(new EncodedComponent(new byte[]{0x0A, 0x08, 0x00}), RegistryAccess.EMPTY));
    }

    @Test
    void bytesLeftAfterTheComponentAreRefused() throws IOException {
        final byte[] title = frozen("title.bin");
        final byte[] longer = Arrays.copyOf(title, title.length + 1);

        assertThrows(IllegalArgumentException.class, () -> PlayComponents.decode(new EncodedComponent(longer), RegistryAccess.EMPTY));
    }

    private static Component decode(String file) throws IOException {
        return PlayComponents.decode(new EncodedComponent(frozen(file)), RegistryAccess.EMPTY);
    }

    private static List<String> arguments(TranslatableContents contents) {
        return Arrays.stream(contents.getArgs()).map(arg -> arg instanceof Component c ? c.getString() : String.valueOf(arg)).toList();
    }

    private static int colour(Component component) {
        final TextColor color = component.getStyle().getColor();
        if (color == null) throw new AssertionError("no colour on " + component);
        return color.getValue();
    }

    static byte[] frozen(String file) throws IOException {
        final String directory = System.getProperty("synaxis.play-vectors.dir");
        if (directory == null) throw new IllegalStateException("the build did not say where the synaxis:play vectors are");
        return Files.readAllBytes(Path.of(directory, "component", file));
    }
}
