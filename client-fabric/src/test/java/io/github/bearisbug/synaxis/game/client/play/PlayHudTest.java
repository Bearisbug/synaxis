package io.github.bearisbug.synaxis.game.client.play;

import io.github.bearisbug.synaxis.game.protocol.play.EncodedComponent;
import io.github.bearisbug.synaxis.game.protocol.play.S2CHud;
import io.github.bearisbug.synaxis.game.protocol.play.S2CScene;
import io.github.bearisbug.synaxis.game.protocol.play.Widget;
import java.io.IOException;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What {@link PlayHud} draws for the state of {@link PlayClient} ({@code docs/DESIGN-PLAY.md} §6.4.3, §6.4.8):
 * nothing before the connection's first scene, which keeps the screenshots of servers without the scene layer
 * as they were; after it the {@code TEXT}s by slot and order, an empty component as an empty line.
 */
class PlayHudTest {

    private static final S2CScene SCENE = new S2CScene(1, "test:scene", EncodedComponent.NONE, 0, 0, List.of());

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void aConnectionThatEnteredNoSceneDrawsNothing() throws IOException {
        final PlayClient play = new PlayClient();
        play.receive(hud(set("hint", 0, component("hint.bin"))), RegistryAccess.EMPTY);

        assertEquals(1, play.texts().size());
        assertEquals(List.of(), PlayHud.lines(play));
    }

    @Test
    void afterASceneTheTextsAreDrawnBySlotAndOrderAndAnEmptyOneAsAnEmptyLine() throws IOException {
        final PlayClient play = new PlayClient();
        play.receive(SCENE, RegistryAccess.EMPTY);
        play.receive(hud(set("empty", 2, EncodedComponent.NONE), set("missing", 1, component("missing.bin")),
                set("hint", 0, component("hint.bin"))), RegistryAccess.EMPTY);

        final List<Component> lines = PlayHud.lines(play);
        assertEquals(List.of("rush.hint", "synaxis_test.missing", ""), lines.stream().map(PlayHudTest::key).toList());
    }

    @Test
    void removeAndClearOwnerTakeTextsOffAndResetForgetsTheScene() throws IOException {
        final PlayClient play = new PlayClient();
        play.receive(SCENE, RegistryAccess.EMPTY);
        play.receive(hud(set("hint", 0, component("hint.bin")), set("missing", 1, component("missing.bin"))), RegistryAccess.EMPTY);
        play.receive(new S2CHud(List.of(new S2CHud.RemoveWidget("test", "hint"))), RegistryAccess.EMPTY);
        assertEquals(List.of("synaxis_test.missing"), PlayHud.lines(play).stream().map(PlayHudTest::key).toList());

        play.receive(new S2CHud(List.of(new S2CHud.ClearOwner("test"))), RegistryAccess.EMPTY);
        assertEquals(List.of(), PlayHud.lines(play));

        play.receive(hud(set("hint", 0, component("hint.bin"))), RegistryAccess.EMPTY);
        play.reset();
        assertEquals(List.of(), play.texts());
        assertEquals(List.of(), PlayHud.lines(play));
    }

    private static S2CHud hud(S2CHud.Op... ops) {
        return new S2CHud(List.of(ops));
    }

    private static S2CHud.Op set(String widgetId, int order, EncodedComponent text) {
        return new S2CHud.SetWidget("test", widgetId, Widget.text(Widget.LEFT, order, text, 0));
    }

    private static EncodedComponent component(String file) throws IOException {
        return new EncodedComponent(PlayComponentDecodeTest.frozen(file));
    }

    private static String key(Component line) {
        return line.getContents() instanceof TranslatableContents contents ? contents.getKey() : line.getString();
    }
}
