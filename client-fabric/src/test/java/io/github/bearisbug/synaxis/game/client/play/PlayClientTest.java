package io.github.bearisbug.synaxis.game.client.play;

import io.github.bearisbug.synaxis.game.protocol.play.ActionResultCode;
import io.github.bearisbug.synaxis.game.protocol.play.C2SAction;
import io.github.bearisbug.synaxis.game.protocol.play.C2SSceneReady;
import io.github.bearisbug.synaxis.game.protocol.play.EncodedComponent;
import io.github.bearisbug.synaxis.game.protocol.play.PlayMessage;
import io.github.bearisbug.synaxis.game.protocol.play.PropValue;
import io.github.bearisbug.synaxis.game.protocol.play.S2CActionResult;
import io.github.bearisbug.synaxis.game.protocol.play.S2CHud;
import io.github.bearisbug.synaxis.game.protocol.play.S2CScene;
import io.github.bearisbug.synaxis.game.protocol.play.Widget;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scene mirror of {@code docs/DESIGN-PLAY.md} §6.4.3, §6.4.4 and §6.4.8 on a fake clock: the four kinds of
 * version 1, the action key with its local cooldown and every result code, the panel's local close and its
 * refused buttons, and the reset of a new connection.
 */
class PlayClientTest {

    private static final String OWNER = "rush";
    private static final long MS = 1_000_000L;

    private final List<PlayMessage> sent = new ArrayList<>();
    private final List<Component> notices = new ArrayList<>();
    private long now = 1_000 * MS;
    private PlayClient play;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void client() {
        play = new PlayClient(sent::add, notices::add, () -> now);
    }

    // ---- The scene and the four kinds

    @Test
    void aSceneBringsItsTitleAndItsActionTableAndItsOwnerIsTheNamespaceOfItsId() {
        play.receive(scene(3, 2000), RegistryAccess.EMPTY);

        final PlayClient.Scene scene = present(play.scene());
        assertEquals(3, scene.seq());
        assertEquals("rush:lobby", scene.id());
        assertEquals("rush", scene.owner());
        assertEquals("rush.scene.lobby", key(scene.title()));
        final PlayClient.Action action = present(scene.action(0));
        assertEquals("rush:queue", action.actionId());
        assertEquals("rush.action.queue", key(action.label()));
        assertEquals(2000, action.cooldownMs());
        assertNull(scene.action(1));
    }

    @Test
    void theFourKindsAreKeptWithTheirPropsAndAnUnknownKindIsDropped() {
        play.receive(scene(1, 0), RegistryAccess.EMPTY);
        play.receive(hud(
                set("hint", Widget.text(Widget.LEFT, 0, encode(Component.literal("hint")), 0)),
                set("timer", Widget.timer(Widget.TOP_CENTER, 0, encode(Component.literal("Time left")), 45_000, true)),
                set("score", Widget.scoreboard(Widget.TOP_RIGHT, 0, encode(Component.literal("Score")), List.of(
                        new PropValue.Row(encode(Component.literal("Ann")), "12", false),
                        new PropValue.Row(encode(Component.literal("Bob")), "7", true)))),
                set("result", Widget.panel(encode(Component.literal("Round over")),
                        List.of(new PropValue.Row(encode(Component.literal("1. Bob")), "7", true)),
                        List.of(new PropValue.Button("rush:back", encode(Component.literal("Back")))))),
                set("bar", new Widget(3, Widget.TOP_CENTER, 1, List.of()))), RegistryAccess.EMPTY);

        assertEquals(List.of("timer", "score", "hint", "result"), play.widgets().stream().map(PlayClient.Entry::widgetId).toList());
        final PlayClient.Timer timer = assertInstanceOf(PlayClient.Timer.class, entry("timer").content());
        assertEquals("Time left", timer.label().getString());
        assertEquals(45_000, timer.remainingMs());
        assertTrue(timer.running());
        final PlayClient.Scoreboard score = assertInstanceOf(PlayClient.Scoreboard.class, entry("score").content());
        assertEquals("Score", score.title().getString());
        assertEquals(List.of("Ann 12 false", "Bob 7 true"),
                score.rows().stream().map(row -> row.label().getString() + " " + row.value() + " " + row.highlight()).toList());
        final PlayClient.Panel panel = assertInstanceOf(PlayClient.Panel.class, entry("result").content());
        assertEquals("Round over", panel.title().getString());
        assertEquals(List.of("rush:back Back"),
                panel.buttons().stream().map(button -> button.actionId() + " " + button.label().getString()).toList());
    }

    @Test
    void widgetsComeBySlotThenTheSceneOwnersFirstThenByOrderThenByTheirFirstSet() {
        play.receive(scene(1, 0), RegistryAccess.EMPTY);
        play.receive(hud(
                new S2CHud.SetWidget("overlay", "o", Widget.text(Widget.LEFT, 0, EncodedComponent.NONE, 0)),
                set("b", Widget.text(Widget.LEFT, 1, EncodedComponent.NONE, 0)),
                set("a", Widget.text(Widget.LEFT, 1, EncodedComponent.NONE, 0)),
                set("top", Widget.text(Widget.TOP_LEFT, 9, EncodedComponent.NONE, 0)),
                set("first", Widget.text(Widget.LEFT, 0, EncodedComponent.NONE, 0))), RegistryAccess.EMPTY);
        // A later SET of "b" keeps its place among equal orders
        play.receive(hud(set("b", Widget.text(Widget.LEFT, 1, encode(Component.literal("again")), 0))), RegistryAccess.EMPTY);

        assertEquals(List.of("top", "first", "b", "a", "o"), play.widgets().stream().map(PlayClient.Entry::widgetId).toList());
    }

    @Test
    void removeAndClearOwnerTakeWidgetsOffAndAnUnknownKindReplacesWhatWasThere() {
        play.receive(scene(1, 0), RegistryAccess.EMPTY);
        play.receive(hud(set("a", Widget.text(Widget.LEFT, 0, EncodedComponent.NONE, 0)),
                set("b", Widget.text(Widget.LEFT, 1, EncodedComponent.NONE, 0)),
                new S2CHud.SetWidget("overlay", "c", Widget.text(Widget.LEFT, 2, EncodedComponent.NONE, 0))), RegistryAccess.EMPTY);
        play.receive(hud(new S2CHud.RemoveWidget(OWNER, "a"), set("b", new Widget(5, Widget.LEFT, 1, List.of()))), RegistryAccess.EMPTY);
        assertEquals(List.of("c"), play.widgets().stream().map(PlayClient.Entry::widgetId).toList());

        play.receive(hud(new S2CHud.ClearOwner("overlay")), RegistryAccess.EMPTY);
        assertEquals(List.of(), play.widgets());
    }

    @Test
    void clearOwnerLeavesTheWidgetsOfEveryOtherOwner() {
        play.receive(scene(1, 0), RegistryAccess.EMPTY);
        play.receive(hud(set("a", Widget.text(Widget.LEFT, 0, EncodedComponent.NONE, 0)),
                new S2CHud.SetWidget("overlay", "o", Widget.text(Widget.LEFT, 1, EncodedComponent.NONE, 0))), RegistryAccess.EMPTY);
        play.receive(hud(new S2CHud.ClearOwner("overlay")), RegistryAccess.EMPTY);

        assertEquals(List.of("a"), play.widgets().stream().map(PlayClient.Entry::widgetId).toList());
    }

    @Test
    void aComponentTheCodecCannotReadShowsAsEmptyTextAndTheRestOfTheMessageStillApplies() {
        play.receive(scene(1, 0), RegistryAccess.EMPTY);
        play.receive(hud(set("bad", Widget.text(Widget.LEFT, 0, new EncodedComponent(new byte[]{0x0A, 0x08, 0x00}), 0)),
                set("good", Widget.text(Widget.LEFT, 1, encode(Component.literal("good")), 0))), RegistryAccess.EMPTY);

        assertEquals(List.of("bad []", "good [good]"), play.texts().stream()
                .map(entry -> entry.widgetId() + " [" + ((PlayClient.Text) entry.content()).text().getString() + "]").toList());
    }

    @Test
    void aRunningTimerCountsDownFromItsSetAndAStoppedOneDoesNot() {
        play.receive(scene(1, 0), RegistryAccess.EMPTY);
        play.receive(hud(set("run", Widget.timer(Widget.TOP_CENTER, 0, EncodedComponent.NONE, 10_000, true)),
                set("stop", Widget.timer(Widget.TOP_CENTER, 1, EncodedComponent.NONE, 10_000, false))), RegistryAccess.EMPTY);
        now += 2_500 * MS;

        assertEquals(7_500, play.remainingMs(entry("run")));
        assertEquals(10_000, play.remainingMs(entry("stop")));
        now += 60_000 * MS;
        assertEquals(0, play.remainingMs(entry("run")));

        // The next SET overwrites it from its own arrival
        play.receive(hud(set("run", Widget.timer(Widget.TOP_CENTER, 0, EncodedComponent.NONE, 3_000, true))), RegistryAccess.EMPTY);
        now += 1_000 * MS;
        assertEquals(2_000, play.remainingMs(entry("run")));
    }

    @Test
    void aTextShowsForItsTimeToLiveAndTheTickAfterThatDropsIt() {
        play.receive(scene(1, 0), RegistryAccess.EMPTY);
        play.receive(hud(set("brief", Widget.text(Widget.BOTTOM, 0, EncodedComponent.NONE, 1_500)),
                set("kept", Widget.text(Widget.BOTTOM, 1, EncodedComponent.NONE, 0))), RegistryAccess.EMPTY);
        now += 1_499 * MS;
        assertEquals(List.of("brief", "kept"), play.texts().stream().map(PlayClient.Entry::widgetId).toList());

        now += MS;
        assertEquals(List.of("kept"), play.texts().stream().map(PlayClient.Entry::widgetId).toList());
        play.tick(outside());
        now -= 10 * MS;
        assertEquals(List.of("kept"), play.texts().stream().map(PlayClient.Entry::widgetId).toList());
    }

    // ---- The action key and its local cooldown

    @Test
    void aPressSendsTheActionOfItsSlotWithTheSceneAndARisingNumberAndStartsTheLocalCooldown() {
        play.receive(scene(4, 2000), RegistryAccess.EMPTY);

        assertEquals(PlayClient.Press.SENT, play.press(0));
        assertEquals(List.of(new C2SAction(4, 1, "rush:queue", new C2SAction.None())), sent);
        assertEquals(2000, play.cooldownLeftMs(0));
        assertEquals(1, play.actionsSent());

        now += 200 * MS;
        assertEquals(PlayClient.Press.COOLING_DOWN, play.press(0));
        assertEquals(1, sent.size());
        assertEquals(1, play.actionsSent());
        assertEquals(List.of("synaxis.action.cooldown [2]"), notices.stream().map(PlayClientTest::keyAndArgs).toList());

        now += 1_800 * MS;
        assertEquals(PlayClient.Press.SENT, play.press(0));
        assertEquals(new C2SAction(4, 2, "rush:queue", new C2SAction.None()), sent.get(1));
    }

    @Test
    void aKeyWithoutAnActionOrAConnectionWithoutASceneSendsNothing() {
        assertEquals(PlayClient.Press.NO_ACTION, play.press(0));
        play.receive(scene(1, 0), RegistryAccess.EMPTY);
        assertEquals(PlayClient.Press.NO_ACTION, play.press(1));
        assertEquals(List.of(), sent);
    }

    @Test
    void withTheLocalCooldownOffAPressGoesOutWhateverTheCooldown() {
        play.receive(scene(1, 2000), RegistryAccess.EMPTY);
        play.press(0);
        play.localCooldown(false);
        now += 200 * MS;

        assertEquals(PlayClient.Press.SENT, play.press(0));
        assertEquals(2, play.actionsSent());
        assertEquals(List.of(), notices);
    }

    @Test
    void okRestartsTheCooldownFromTheServersAndCooldownFromWhatIsLeft() {
        play.receive(scene(1, 2000), RegistryAccess.EMPTY);
        play.press(0);
        now += 500 * MS;
        play.receive(result(1, ActionResultCode.OK, 2000), RegistryAccess.EMPTY);
        assertEquals(2000, play.cooldownLeftMs(0));

        play.localCooldown(false);
        play.press(0);
        play.receive(result(2, ActionResultCode.COOLDOWN, 1_300), RegistryAccess.EMPTY);
        assertEquals(1_300, play.cooldownLeftMs(0));
        assertEquals(List.of("synaxis.action.cooldown [2]"), notices.stream().map(PlayClientTest::keyAndArgs).toList());
        final PlayClient.Result last = present(play.lastResult());
        assertEquals(2, last.actionSeq());
        assertEquals(ActionResultCode.COOLDOWN, last.code());
        assertEquals(1_300, last.cooldownMs());
        assertEquals(now, last.receivedNanos());
    }

    @Test
    void aCooldownWithTheHostsReasonShowsTheReason() {
        play.receive(scene(1, 2000), RegistryAccess.EMPTY);
        play.press(0);
        play.receive(new S2CActionResult(1, ActionResultCode.COOLDOWN,
                encode(Component.translatable("synaxis:action-cooldown", "2")), 1_500), RegistryAccess.EMPTY);

        assertEquals(List.of("synaxis:action-cooldown [2]"), notices.stream().map(PlayClientTest::keyAndArgs).toList());
    }

    @Test
    void rejectedClearsTheCooldownAndShowsTheReasonOrTheClientsTextWhenThereIsNone() {
        play.receive(scene(1, 2000), RegistryAccess.EMPTY);
        play.press(0);
        play.receive(new S2CActionResult(1, ActionResultCode.REJECTED, encode(Component.translatable("rush.hint.failed")), 0),
                RegistryAccess.EMPTY);
        assertEquals(0, play.cooldownLeftMs(0));

        play.press(0);
        play.receive(result(2, ActionResultCode.REJECTED, 0), RegistryAccess.EMPTY);
        // A code this version does not know is shown as rejected (§9)
        play.press(0);
        play.receive(new S2CActionResult(3, 200, EncodedComponent.NONE, 0), RegistryAccess.EMPTY);

        assertEquals(List.of("rush.hint.failed []", "synaxis.action.rejected []", "synaxis.action.rejected []"),
                notices.stream().map(PlayClientTest::keyAndArgs).toList());
        assertEquals(0, play.cooldownLeftMs(0));
    }

    @Test
    void rateLimitedAndUnknownActionClearTheCooldownSilentlyAndStaleSceneLeavesIt() {
        play.receive(scene(1, 2000), RegistryAccess.EMPTY);
        play.press(0);
        play.receive(result(1, ActionResultCode.RATE_LIMITED, 0), RegistryAccess.EMPTY);
        assertEquals(0, play.cooldownLeftMs(0));
        play.press(0);
        play.receive(result(2, ActionResultCode.UNKNOWN_ACTION, 0), RegistryAccess.EMPTY);
        assertEquals(0, play.cooldownLeftMs(0));
        play.press(0);
        play.receive(result(3, ActionResultCode.STALE_SCENE, 0), RegistryAccess.EMPTY);
        assertEquals(2000, play.cooldownLeftMs(0));
        assertEquals(List.of(), notices);
    }

    @Test
    void aNewSceneEndsEveryCooldownAndAResultOfTheSceneBeforeLeavesTheNewOneAlone() {
        play.receive(scene(1, 2000), RegistryAccess.EMPTY);
        play.press(0);
        play.receive(scene(2, 2000), RegistryAccess.EMPTY);
        assertEquals(0, play.cooldownLeftMs(0));

        play.receive(result(1, ActionResultCode.OK, 2000), RegistryAccess.EMPTY);
        assertEquals(0, play.cooldownLeftMs(0));
        assertEquals(PlayClient.Press.SENT, play.press(0));
        assertEquals(new C2SAction(2, 2, "rush:queue", new C2SAction.None()), sent.get(1));
    }

    @Test
    void aCooldownAnsweringTheSceneBeforeLeavesTheNewTablesKeyFree() {
        play.receive(scene(1, 2000), RegistryAccess.EMPTY);
        play.press(0);
        play.receive(scene(2, 2000), RegistryAccess.EMPTY);

        play.receive(result(1, ActionResultCode.COOLDOWN, 1_500), RegistryAccess.EMPTY);
        assertEquals(0, play.cooldownLeftMs(0));
        assertEquals(PlayClient.Press.SENT, play.press(0));
    }

    @Test
    void aRefusalAnsweringTheSceneBeforeLeavesTheCooldownsTheNewTableStarted() {
        final List<S2CScene.Action> keys = List.of(new S2CScene.Action(0, "rush:a", EncodedComponent.NONE, 2000),
                new S2CScene.Action(1, "rush:b", EncodedComponent.NONE, 2000),
                new S2CScene.Action(2, "rush:c", EncodedComponent.NONE, 2000));
        play.receive(new S2CScene(1, "rush:lobby", EncodedComponent.NONE, S2CScene.CAMERA_VANILLA, S2CScene.CURSOR_LOCKED, keys),
                RegistryAccess.EMPTY);
        for (int slot = 0; slot < 3; slot++) play.press(slot);
        play.receive(new S2CScene(2, "rush:lobby", EncodedComponent.NONE, S2CScene.CAMERA_VANILLA, S2CScene.CURSOR_LOCKED, keys),
                RegistryAccess.EMPTY);
        for (int slot = 0; slot < 3; slot++) play.press(slot);

        play.receive(result(1, ActionResultCode.REJECTED, 0), RegistryAccess.EMPTY);
        play.receive(result(2, ActionResultCode.RATE_LIMITED, 0), RegistryAccess.EMPTY);
        play.receive(result(3, ActionResultCode.UNKNOWN_ACTION, 0), RegistryAccess.EMPTY);
        assertEquals(List.of(2000L, 2000L, 2000L), IntStream.range(0, 3).mapToObj(play::cooldownLeftMs).toList());
    }

    @Test
    void aResultForNoActionThisConnectionSentIsOnlyRecorded() {
        play.receive(scene(1, 2000), RegistryAccess.EMPTY);
        play.press(0);
        play.receive(new S2CActionResult(9, ActionResultCode.REJECTED, encode(Component.translatable("rush.hint.failed")), 0),
                RegistryAccess.EMPTY);

        assertEquals(9, present(play.lastResult()).actionSeq());
        assertEquals(2000, play.cooldownLeftMs(0));
        assertEquals(List.of(), notices);
    }

    // ---- The panel

    @Test
    void thePanelIsShownUntilItIsClosedLocallyAndItsNextSetShowsItAgain() {
        play.receive(scene(1, 0), RegistryAccess.EMPTY);
        assertNull(play.panel());
        play.receive(hud(panel("result", "rush:back")), RegistryAccess.EMPTY);
        final PlayClient.Entry shown = present(play.panel());
        assertEquals("result", shown.widgetId());

        play.dismiss(shown);
        assertNull(play.panel());
        assertEquals(1, play.widgets().size());

        play.receive(hud(panel("result", "rush:back")), RegistryAccess.EMPTY);
        assertEquals("result", present(play.panel()).widgetId());
        play.receive(hud(new S2CHud.RemoveWidget(OWNER, "result")), RegistryAccess.EMPTY);
        assertNull(play.panel());
    }

    @Test
    void aPanelWithoutASceneIsNotShown() {
        play.receive(hud(panel("result", "rush:back")), RegistryAccess.EMPTY);
        assertNull(play.panel());
    }

    @Test
    void thePanelSkipsTheOtherKindsThatComeBeforeItInLayoutOrder() {
        play.receive(scene(1, 0), RegistryAccess.EMPTY);
        play.receive(hud(set("hint", Widget.text(Widget.TOP_LEFT, 0, EncodedComponent.NONE, 0)),
                set("timer", Widget.timer(Widget.TOP_CENTER, 0, EncodedComponent.NONE, 10_000, true)),
                set("score", Widget.scoreboard(Widget.TOP_RIGHT, 0, EncodedComponent.NONE, List.of())),
                panel("result", "rush:back")), RegistryAccess.EMPTY);

        assertEquals("result", present(play.panel()).widgetId());
    }

    @Test
    void aClickedButtonThatIsRefusedKeepsThePanelWithTheReasonAndOkClosesIt() {
        play.receive(scene(5, 0), RegistryAccess.EMPTY);
        play.receive(hud(panel("result", "rush:back")), RegistryAccess.EMPTY);
        final PlayClient.Entry shown = present(play.panel());
        final PlayClient.Button back = ((PlayClient.Panel) shown.content()).buttons().getFirst();

        play.click(shown, back);
        assertEquals(List.of(new C2SAction(5, 1, "rush:back", new C2SAction.None())), sent);
        play.receive(new S2CActionResult(1, ActionResultCode.REJECTED, encode(Component.translatable("rush.hint.failed")), 0),
                RegistryAccess.EMPTY);
        assertEquals(shown, play.panel());
        assertEquals("rush.hint.failed", key(present(play.panelReason(shown))));
        assertEquals(List.of(), notices);

        play.click(shown, back);
        assertNull(play.panelReason(shown));
        play.receive(result(2, ActionResultCode.COOLDOWN, 3_000), RegistryAccess.EMPTY);
        assertEquals("synaxis.action.cooldown [3]", keyAndArgs(present(play.panelReason(shown))));

        play.click(shown, back);
        play.receive(result(3, ActionResultCode.RATE_LIMITED, 0), RegistryAccess.EMPTY);
        assertEquals(shown, play.panel());
        play.click(shown, back);
        play.receive(result(4, ActionResultCode.OK, 0), RegistryAccess.EMPTY);
        assertNull(play.panel());
        assertEquals(1, play.widgets().size());
    }

    @Test
    void anOkForAPanelThatWasSetAgainAfterTheClickLeavesTheNewContentShown() {
        play.receive(scene(5, 0), RegistryAccess.EMPTY);
        play.receive(hud(panel("result", "rush:back")), RegistryAccess.EMPTY);
        final PlayClient.Entry clicked = present(play.panel());
        play.click(clicked, ((PlayClient.Panel) clicked.content()).buttons().getFirst());
        play.receive(hud(panel("result", "rush:back")), RegistryAccess.EMPTY);
        final PlayClient.Entry next = present(play.panel());

        play.receive(result(1, ActionResultCode.OK, 0), RegistryAccess.EMPTY);
        assertEquals(next, play.panel());
    }

    @Test
    void aRefusalShowsOnlyOnTheContentWhoseButtonWasClicked() {
        play.receive(scene(5, 0), RegistryAccess.EMPTY);
        play.receive(hud(panel("result", "rush:back")), RegistryAccess.EMPTY);
        final PlayClient.Entry first = present(play.panel());
        play.click(first, ((PlayClient.Panel) first.content()).buttons().getFirst());
        play.receive(new S2CActionResult(1, ActionResultCode.REJECTED, encode(Component.translatable("rush.hint.failed")), 0),
                RegistryAccess.EMPTY);
        assertEquals("rush.hint.failed", key(present(play.panelReason(first))));

        // The next SET of the same panel is new content, which nothing was refused on
        play.receive(hud(panel("result", "rush:back")), RegistryAccess.EMPTY);
        final PlayClient.Entry second = present(play.panel());
        assertNull(play.panelReason(second));

        // A refusal that arrives after the panel was set again is about the content that was clicked
        play.click(second, ((PlayClient.Panel) second.content()).buttons().getFirst());
        play.receive(hud(panel("result", "rush:back")), RegistryAccess.EMPTY);
        final PlayClient.Entry third = present(play.panel());
        play.receive(result(2, ActionResultCode.COOLDOWN, 3_000), RegistryAccess.EMPTY);
        assertNull(play.panelReason(third));
    }

    @Test
    void aClickOnAnotherPanelLeavesTheReasonOnThePanelThatWasRefused() {
        play.receive(scene(5, 0), RegistryAccess.EMPTY);
        play.receive(hud(new S2CHud.SetWidget("overlay", "invite", Widget.panel(encode(Component.literal("Invite")), List.of(),
                List.of(new PropValue.Button("overlay:join", encode(Component.literal("Join"))))))), RegistryAccess.EMPTY);
        final PlayClient.Entry invite = present(play.panel());
        play.click(invite, ((PlayClient.Panel) invite.content()).buttons().getFirst());
        play.receive(new S2CActionResult(1, ActionResultCode.REJECTED, encode(Component.translatable("overlay.invite.full")), 0),
                RegistryAccess.EMPTY);

        // The scene owner's panel comes first (§6.4.8), and the ok of its button closes it here
        play.receive(hud(panel("result", "rush:back")), RegistryAccess.EMPTY);
        final PlayClient.Entry front = present(play.panel());
        assertEquals("result", front.widgetId());
        play.click(front, ((PlayClient.Panel) front.content()).buttons().getFirst());
        play.receive(result(2, ActionResultCode.OK, 0), RegistryAccess.EMPTY);

        // The overlay's panel is back, still showing why its own button was refused
        assertEquals(invite, play.panel());
        assertEquals("overlay.invite.full", key(present(play.panelReason(invite))));
    }

    @Test
    void aRefusedButtonWithoutAReasonShowsTheClientsTextAndACooldownWithTheHostsReasonShowsThatReason() {
        play.receive(scene(5, 0), RegistryAccess.EMPTY);
        play.receive(hud(panel("result", "rush:back")), RegistryAccess.EMPTY);
        final PlayClient.Entry shown = present(play.panel());
        final PlayClient.Button back = ((PlayClient.Panel) shown.content()).buttons().getFirst();
        final List<String> reasons = new ArrayList<>();

        play.click(shown, back);
        play.receive(result(1, ActionResultCode.REJECTED, 0), RegistryAccess.EMPTY);
        reasons.add(reasonOn(shown));
        play.click(shown, back);
        play.receive(new S2CActionResult(2, ActionResultCode.COOLDOWN,
                encode(Component.translatable("synaxis:action-cooldown", "2")), 3_000), RegistryAccess.EMPTY);
        reasons.add(reasonOn(shown));

        assertEquals(List.of("synaxis.action.rejected []", "synaxis:action-cooldown [2]"), reasons);
        assertEquals(List.of(), notices);
    }

    @Test
    void anOkForAPanelNoLongerInTheTableChangesNothing() {
        play.receive(scene(5, 0), RegistryAccess.EMPTY);
        play.receive(hud(panel("result", "rush:back")), RegistryAccess.EMPTY);
        final PlayClient.Entry clicked = present(play.panel());
        play.click(clicked, ((PlayClient.Panel) clicked.content()).buttons().getFirst());
        // The host may take the panel off (a scene switch clears its owner's widgets) before the result arrives
        play.receive(hud(new S2CHud.RemoveWidget(OWNER, "result")), RegistryAccess.EMPTY);

        play.receive(result(1, ActionResultCode.OK, 0), RegistryAccess.EMPTY);
        assertEquals(List.of(), play.widgets());
        play.receive(hud(panel("result", "rush:back")), RegistryAccess.EMPTY);
        assertEquals("result", present(play.panel()).widgetId());
    }

    // ---- Readiness and a new connection

    @Test
    void theTickAnswersTheSceneOnceTheClientStandsInALoadedLevel() {
        play.receive(scene(1, 0), RegistryAccess.EMPTY);
        play.tick(outside());
        assertEquals(List.of(), sent);
        play.tick(new SceneReadyProbe.View(true, null, true));
        play.tick(new SceneReadyProbe.View(true, null, true));

        assertEquals(List.of(new C2SSceneReady(1)), sent);
        assertEquals(1, present(play.probe().last()).sceneSeq());
    }

    @Test
    void resetForgetsTheSceneTheWidgetsTheActionsAndTheReadiness() {
        play.receive(scene(1, 2000), RegistryAccess.EMPTY);
        play.receive(hud(panel("result", "rush:back")), RegistryAccess.EMPTY);
        play.press(0);
        play.tick(new SceneReadyProbe.View(true, null, true));
        play.reset();

        assertNull(play.scene());
        assertEquals(List.of(), play.widgets());
        assertNull(play.panel());
        assertNull(play.lastResult());
        assertNull(play.probe().last());
        assertEquals(0, play.actionsSent());
        assertEquals(0, play.cooldownLeftMs(0));

        play.receive(scene(1, 0), RegistryAccess.EMPTY);
        play.receive(scene(1, 2000), RegistryAccess.EMPTY);
        play.press(0);
        assertEquals(new C2SAction(1, 1, "rush:queue", new C2SAction.None()), sent.getLast());
        play.tick(new SceneReadyProbe.View(true, null, true));
        assertEquals(new C2SSceneReady(1), sent.getLast());
    }

    @Test
    void resetForgetsThePanelsClosedLocallySoTheNewConnectionsPanelShows() {
        play.receive(scene(1, 0), RegistryAccess.EMPTY);
        play.receive(hud(panel("result", "rush:back")), RegistryAccess.EMPTY);
        play.dismiss(present(play.panel()));
        play.reset();

        // The new connection numbers its SETs from 1 again, so its first one carries the number the closed one had
        play.receive(scene(1, 0), RegistryAccess.EMPTY);
        play.receive(hud(panel("result", "rush:back")), RegistryAccess.EMPTY);
        assertEquals("result", present(play.panel()).widgetId());
    }

    @Test
    void resetForgetsWhatWasRefusedOnAPanel() {
        play.receive(scene(1, 0), RegistryAccess.EMPTY);
        play.receive(hud(panel("result", "rush:back")), RegistryAccess.EMPTY);
        final PlayClient.Entry refused = present(play.panel());
        play.click(refused, ((PlayClient.Panel) refused.content()).buttons().getFirst());
        play.receive(new S2CActionResult(1, ActionResultCode.REJECTED, encode(Component.translatable("rush.hint.failed")), 0),
                RegistryAccess.EMPTY);
        play.reset();

        play.receive(scene(1, 0), RegistryAccess.EMPTY);
        play.receive(hud(panel("result", "rush:back")), RegistryAccess.EMPTY);
        assertNull(play.panelReason(present(play.panel())));
    }

    @Test
    void resetForgetsTheActionsStillWaitingForTheirResult() {
        play.receive(scene(1, 0), RegistryAccess.EMPTY);
        play.press(0);
        play.press(0);
        play.reset();

        // The new connection sent action 1 only, so a result for action 2 answers nothing it sent
        play.receive(scene(1, 2000), RegistryAccess.EMPTY);
        play.press(0);
        play.receive(new S2CActionResult(2, ActionResultCode.REJECTED, encode(Component.translatable("rush.hint.failed")), 0),
                RegistryAccess.EMPTY);

        assertEquals(2000, play.cooldownLeftMs(0));
        assertEquals(List.of(), notices);
    }

    // ---- Helpers

    private PlayClient.Entry entry(String widgetId) {
        return play.widgets().stream().filter(entry -> entry.widgetId().equals(widgetId)).findFirst()
                .orElseThrow(() -> new AssertionError("no widget " + widgetId));
    }

    private String reasonOn(PlayClient.Entry panel) {
        final Component reason = play.panelReason(panel);
        return reason == null ? "none" : keyAndArgs(reason);
    }

    private static SceneReadyProbe.View outside() {
        return new SceneReadyProbe.View(false, null, false);
    }

    static S2CScene scene(int seq, int cooldownMs) {
        return new S2CScene(seq, "rush:lobby", encode(Component.translatable("rush.scene.lobby")), S2CScene.CAMERA_VANILLA,
                S2CScene.CURSOR_LOCKED, List.of(new S2CScene.Action(0, "rush:queue", encode(Component.translatable("rush.action.queue")),
                cooldownMs)));
    }

    private static S2CActionResult result(int actionSeq, ActionResultCode code, int cooldownMs) {
        return new S2CActionResult(actionSeq, code, EncodedComponent.NONE, cooldownMs);
    }

    static S2CHud hud(S2CHud.Op... ops) {
        return new S2CHud(List.of(ops));
    }

    static S2CHud.SetWidget set(String widgetId, Widget widget) {
        return new S2CHud.SetWidget(OWNER, widgetId, widget);
    }

    private static S2CHud.SetWidget panel(String widgetId, String button) {
        return set(widgetId, Widget.panel(encode(Component.literal("Round over")), List.of(),
                List.of(new PropValue.Button(button, encode(Component.literal("Back"))))));
    }

    /** A component in the bytes the host's {@code ComponentBytes} writes: vanilla's network format. */
    static EncodedComponent encode(Component component) {
        final RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        ComponentSerialization.STREAM_CODEC.encode(buf, component);
        final byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return new EncodedComponent(bytes);
    }

    static String key(Component component) {
        return component.getContents() instanceof TranslatableContents contents ? contents.getKey() : component.getString();
    }

    static <T> T present(@Nullable T value) {
        if (value == null) throw new AssertionError("expected a value, got null");
        return value;
    }

    private static String keyAndArgs(Component component) {
        final TranslatableContents contents = assertInstanceOf(TranslatableContents.class, component.getContents());
        return contents.getKey() + " " + Arrays.stream(contents.getArgs())
                .map(arg -> arg instanceof Component c ? c.getString() : String.valueOf(arg)).toList();
    }
}
