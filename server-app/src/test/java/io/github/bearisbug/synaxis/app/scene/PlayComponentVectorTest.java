package io.github.bearisbug.synaxis.app.scene;

import io.github.bearisbug.synaxis.game.protocol.MsgBuf;
import io.github.bearisbug.synaxis.game.protocol.play.EncodedComponent;
import io.github.bearisbug.synaxis.game.protocol.play.PlayMessages;
import io.github.bearisbug.synaxis.game.protocol.play.PlayProtocol;
import io.github.bearisbug.synaxis.game.protocol.play.PropValue;
import io.github.bearisbug.synaxis.game.protocol.play.S2CHud;
import io.github.bearisbug.synaxis.game.protocol.play.S2CScene;
import io.github.bearisbug.synaxis.game.protocol.play.Widget;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server half of the first step of {@code docs/DESIGN-PLAY.md} §26 S1: {@link ComponentBytes} writes the
 * components of {@link PlayHudFixture} as the bytes frozen under {@code play-vectors/component/}, which the
 * client's {@code PlayComponentDecodeTest} decodes with the vanilla component stream codec. The build points
 * {@code synaxis.play-vectors.dir} at {@code protocol/src/test/resources/play-vectors} and makes the
 * {@code component/} directory an input of this task, so a changed file runs this again.
 * <p>
 * When a comparison fails, the message carries the bytes {@link ComponentBytes} wrote now, in hex: written to
 * the file, they freeze the engine's new output, which the client test then has to decode.
 */
class PlayComponentVectorTest {

    @Test
    void aKeyWithoutArgumentsIsTheFrozenTitle() throws IOException {
        assertFrozen("title.bin", PlayHudFixture.TITLE);
    }

    @Test
    void aKeyWithOneArgumentAColourAndAFallbackIsTheFrozenHint() throws IOException {
        assertFrozen("hint.bin", PlayHudFixture.HINT);
    }

    @Test
    void aKeyTheLanguageFilesLackKeepsItsFallbackInTheFrozenBytes() throws IOException {
        assertFrozen("missing.bin", PlayHudFixture.MISSING);
    }

    @Test
    void theFixtureSendsTheFrozenComponentsInASceneAndThenAHud() throws IOException {
        final S2CScene scene = (S2CScene) PlayMessages.read(MsgBuf.reading(PlayHudFixture.encode(PlayHudFixture.scene())));
        final byte[] hudBytes = PlayHudFixture.encode(PlayHudFixture.hud());
        final S2CHud hud = (S2CHud) PlayMessages.read(MsgBuf.reading(hudBytes));

        assertEquals(1, scene.sceneSeq());
        assertEquals(new EncodedComponent(frozen("title.bin")), scene.title());
        assertEquals(List.of("hint", "missing", "empty"), hud.ops().stream().map(S2CHud.Op::widgetId).toList());
        assertEquals(List.of(new EncodedComponent(frozen("hint.bin")), new EncodedComponent(frozen("missing.bin")),
                EncodedComponent.NONE), hud.ops().stream().map(PlayComponentVectorTest::text).toList());
        assertTrue(hudBytes.length <= PlayProtocol.MAX_MESSAGE_BYTES, hudBytes.length + " B");
    }

    private static EncodedComponent text(S2CHud.Op op) {
        final Widget widget = ((S2CHud.SetWidget) op).widget();
        assertEquals(Widget.TEXT, widget.kind());
        return ((PropValue.Component) widget.prop(Widget.PROP_TEXT).orElseThrow()).value();
    }

    private static void assertFrozen(String file, Component component) throws IOException {
        final String written = HexFormat.of().formatHex(ComponentBytes.encode(component));
        assertEquals(HexFormat.of().formatHex(frozen(file)), written, () -> "ComponentBytes no longer writes "
                + directory().resolve(file) + "; it writes " + written);
    }

    private static byte[] frozen(String file) throws IOException {
        return Files.readAllBytes(directory().resolve(file));
    }

    private static Path directory() {
        final String directory = System.getProperty("synaxis.play-vectors.dir");
        if (directory == null) throw new IllegalStateException("the build did not say where the synaxis:play vectors are");
        return Path.of(directory, "component");
    }
}
