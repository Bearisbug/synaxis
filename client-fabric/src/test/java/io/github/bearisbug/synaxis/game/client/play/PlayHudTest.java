package io.github.bearisbug.synaxis.game.client.play;

import io.github.bearisbug.synaxis.game.protocol.play.EncodedComponent;
import io.github.bearisbug.synaxis.game.protocol.play.PropValue;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link PlayHud} draws for the state of {@link PlayClient} ({@code docs/DESIGN-PLAY.md} §6.4.3, §6.4.8):
 * nothing before the connection's first scene, which keeps the screenshots of servers without the scene layer
 * as they were; after it the widgets in layout order, a {@code TEXT} as its line (an empty component as an empty
 * line), a {@code TIMER} as its label and the time left, a {@code SCOREBOARD} as its title and its rows, and no
 * {@code PANEL}, which is a screen of its own.
 */
class PlayHudTest {

    private static final S2CScene SCENE = new S2CScene(1, "test:scene", EncodedComponent.NONE, 0, 0, List.of());
    private static final long MS = 1_000_000L;

    private long now = 7_000 * MS;
    private final PlayClient play = new PlayClient(_ -> {
    }, _ -> {
    }, () -> now);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void aConnectionThatEnteredNoSceneDrawsNothing() throws IOException {
        play.receive(hud(set("hint", 0, component("hint.bin"))), RegistryAccess.EMPTY);

        assertEquals(1, play.texts().size());
        assertEquals(List.of(), PlayHud.blocks(play));
    }

    @Test
    void afterASceneTheTextsAreDrawnBySlotAndOrderAndAnEmptyOneAsAnEmptyLine() throws IOException {
        play.receive(SCENE, RegistryAccess.EMPTY);
        play.receive(hud(set("empty", 2, EncodedComponent.NONE), set("missing", 1, component("missing.bin")),
                set("hint", 0, component("hint.bin"))), RegistryAccess.EMPTY);

        assertEquals(List.of("synaxis.action.cooldown", "synaxis_test.missing", ""), lines(play));
    }

    @Test
    void removeAndClearOwnerTakeTextsOffAndResetForgetsTheScene() throws IOException {
        play.receive(SCENE, RegistryAccess.EMPTY);
        play.receive(hud(set("hint", 0, component("hint.bin")), set("missing", 1, component("missing.bin"))), RegistryAccess.EMPTY);
        play.receive(new S2CHud(List.of(new S2CHud.RemoveWidget("test", "hint"))), RegistryAccess.EMPTY);
        assertEquals(List.of("synaxis_test.missing"), lines(play));

        play.receive(new S2CHud(List.of(new S2CHud.ClearOwner("test"))), RegistryAccess.EMPTY);
        assertEquals(List.of(), PlayHud.blocks(play));

        play.receive(hud(set("hint", 0, component("hint.bin"))), RegistryAccess.EMPTY);
        play.reset();
        assertEquals(List.of(), play.texts());
        assertEquals(List.of(), PlayHud.blocks(play));
    }

    @Test
    void aTimerShowsItsLabelAndTheTimeLeftAndAScoreboardItsTitleAndRowsButAPanelIsNotOnTheHud() {
        play.receive(SCENE, RegistryAccess.EMPTY);
        play.receive(hud(
                new S2CHud.SetWidget("test", "timer", Widget.timer(Widget.TOP_CENTER, 0,
                        PlayClientTest.encode(Component.literal("Time left")), 45_000, true)),
                new S2CHud.SetWidget("test", "score", Widget.scoreboard(Widget.TOP_RIGHT, 0,
                        PlayClientTest.encode(Component.literal("Score")), List.of(
                                new PropValue.Row(PlayClientTest.encode(Component.literal("Ann")), "12", false),
                                new PropValue.Row(PlayClientTest.encode(Component.literal("Bob")), "7", true)))),
                new S2CHud.SetWidget("test", "result", Widget.panel(EncodedComponent.NONE, List.of(), List.of()))),
                RegistryAccess.EMPTY);
        now += 1_200 * MS;

        final List<PlayHud.Block> blocks = PlayHud.blocks(play);
        assertEquals(List.of("timer", "score"), blocks.stream().map(block -> block.entry().widgetId()).toList());
        assertEquals(List.of("Time left 0:44"), blocks.get(0).lines().stream().map(line -> line.text().getString()).toList());
        assertEquals(List.of("Score null false", "Ann 12 false", "Bob 7 true"), blocks.get(1).lines().stream()
                .map(line -> line.text().getString() + " " + line.value() + " " + line.highlight()).toList());
        assertTrue(blocks.get(1).framed());
    }

    @Test
    void theTimeLeftIsMinutesAndSecondsRoundedUp() {
        assertEquals("0:00", PlayHud.clock(0));
        assertEquals("0:01", PlayHud.clock(1));
        assertEquals("0:45", PlayHud.clock(45_000));
        assertEquals("1:00", PlayHud.clock(59_001));
        assertEquals("4:00", PlayHud.clock(240_000));
        assertEquals("0:00", PlayHud.clock(-5));
        assertEquals("0:09", PlayHud.timerLine(Component.empty(), 8_500).getString());
    }

    private static List<String> lines(PlayClient play) {
        return PlayHud.blocks(play).stream().flatMap(block -> block.lines().stream()).map(line -> key(line.text())).toList();
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
