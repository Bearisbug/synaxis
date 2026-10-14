package io.github.bearisbug.synaxis.game.client.gametest.play;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.bearisbug.synaxis.game.client.ClientHandshake;
import io.github.bearisbug.synaxis.game.client.play.ActionKeys;
import io.github.bearisbug.synaxis.game.client.play.PanelScreen;
import io.github.bearisbug.synaxis.game.client.play.PlayClient;
import io.github.bearisbug.synaxis.game.client.play.PlayPayload;
import io.github.bearisbug.synaxis.game.client.play.SceneReadyProbe;
import io.github.bearisbug.synaxis.game.protocol.play.ActionResultCode;
import io.github.bearisbug.synaxis.game.protocol.play.PlayProtocol;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * The real client script of the sample game {@code rush} ({@code docs/DESIGN-PLAY.md} §6.7.6), run by job
 * {@code rush} of {@code client-gametest.yml}. With neither {@value #RUSH_VARIABLE} nor {@value #FIXTURE_VARIABLE}
 * set it returns at once, so every other job that starts the same entry points is untouched.
 * <p>
 * Q0 is the first step of §26 S1, against {@code PlayHudFixture} at {@value #FIXTURE_VARIABLE}. Q0a: the scene and
 * the three {@code TEXT}s it sends decode into the keys, arguments, colours and fallbacks it sent, the HUD draws
 * the language file's text for the key the mod has, the fallback for the key it lacks and nothing for the empty
 * component, and screenshot {@code play-text} holds the two colours. Q0b: once the client asks over the test
 * channel, the fixture moves the player into an instance of another dimension name; every client tick from the
 * request on records the open screen, the level's dimension and whether the chunk under the player is there,
 * until the client stands in the new level with no screen open and that chunk loaded. Q0c (group C): the fixture
 * enters the player into a second scene with one action key, a {@code TIMER}, a {@code SCOREBOARD}, a {@code TEXT}
 * at the bottom and a {@code PANEL}; the panel opens without pausing and Esc closes it locally, screenshots
 * {@code play-panel} and {@code play-widgets} hold each widget's colour in its slot's part of the screen, the
 * timer counts down, the action key sends once and the local cooldown holds back the next press until it is
 * turned off, the panel opened again keeps a refused button's reason and closes on the accepted one, the
 * fixture got one {@code C2S_SCENE_READY} for each scene, and {@link PlayTestHook}'s recording holds only what
 * came after it began. Q1 to Q9 are the steps of §6.7.6 against the sample game at {@value #RUSH_VARIABLE},
 * one round played alone with {@code synaxis.rush.min-players=1}: the handshake and the action key's default key
 * (Q1), the lobby (Q2), the queue key answered {@code ok} and then held back by the local and the server's
 * cooldown, one press at a time since the recording samples once a tick (Q3, Q4), the move into the arena and the
 * client's readiness there (Q5), a beacon taken (Q6), the {@code TIMER}'s extrapolation measured on the recording
 * (Q7), the result panel (Q8) and its button back to the lobby (Q9).
 * <p>
 * Every step is one line {@code rush Q<n> PASS|FAIL|SKIP <evidence>} of {@code synaxis-gametest/rush.txt},
 * written in a {@code finally}; the test catches what its steps throw and never fails itself, and
 * {@code client-fabric/gametest-env/rush_judge.py} judges the file.
 */
public final class RushClientGameTest implements FabricClientGameTest {
    static final String RUSH_VARIABLE = "SYNAXIS_RUSH_SERVER";
    static final String FIXTURE_VARIABLE = "SYNAXIS_PLAY_FIXTURE_SERVER";

    /** The lines of the report, in the order they are written. */
    private static final List<String> STEPS = List.of("Q0a", "Q0b", "Q0c", "Q1", "Q2", "Q3", "Q4", "Q5", "Q6", "Q7", "Q8", "Q9");

    // What PlayHudFixture sends, the components frozen under play-vectors/component/
    private static final String SCENE_ID = "synaxis_test:play";
    private static final String TITLE_KEY = "rush.scene.lobby";
    private static final String HINT_KEY = "rush.hint";
    private static final String HINT_ARGUMENT = "1";
    private static final int HINT_COLOUR = 0xFFAA00;
    private static final String HINT_FALLBACK = "The fallback of rush.hint, queued: %s";
    private static final String MISSING_KEY = "synaxis_test.missing";
    private static final int MISSING_COLOUR = 0x55FF55;
    private static final String MISSING_FALLBACK = "Shown from the fallback";
    private static final List<String> WIDGETS = List.of("hint", "missing", "empty");
    private static final String ELSEWHERE = "synaxis_test:play_elsewhere";
    // Q0c: the second scene of PlayHudFixture, its widgets' colours and its panel's two buttons
    private static final String WIDGETS_SCENE_ID = "synaxis_test:widgets";
    private static final int TIMER_COLOUR = 0x55FFFF;
    private static final int SCORE_COLOUR = 0xFFFF55;
    private static final int VALUE_COLOUR = 0xFF5555;
    private static final int CAPTURE_COLOUR = 0xFF55FF;
    private static final int REFUSE_BUTTON = 0;
    private static final int CLOSE_BUTTON = 1;
    private static final int POKE_COOLDOWN_MS = 2000;
    /** How far above the bottom edge the action bar starts, in GUI pixels (WidgetLayout.ACTION_BAR_TOP). */
    private static final int ACTION_BAR_TOP = 72;
    private static final int PANEL_TIMEOUT_TICKS = 20 * 5;
    private static final int RESULT_TIMEOUT_TICKS = 20 * 2;

    private static final int CONNECT_TIMEOUT_TICKS = 20 * 120;
    private static final int HUD_TIMEOUT_TICKS = 20 * 20;
    private static final int FRAME_TICKS = 10;
    private static final int MOVE_TIMEOUT_TICKS = 20 * 30;
    private static final int ANSWER_TIMEOUT_TICKS = 20 * 5;
    /** A colour shows on the screenshot when at least this many pixels are within {@value #NEAR} of it. */
    private static final int MIN_PIXELS = 20;
    private static final int NEAR = 24;

    // Q1 to Q9: the sample game with the CI values of docs/DESIGN-PLAY.md §6.7.5
    private static final String RUSH = "rush";
    private static final String LOBBY_SCENE = "rush:lobby";
    private static final String ROUND_SCENE = "rush:round";
    private static final String LOBBY_DIMENSION = "rush:lobby_";
    private static final String ROUND_DIMENSION = "rush:round_";
    private static final String QUEUE_ACTION = "rush:queue";
    private static final String HINT = "hint";
    private static final String TIMER = "timer";
    private static final String CAPTURE = "capture";
    private static final String SCORE = "score";
    private static final String RESULT = "result";
    private static final long NANOS_PER_MS = 1_000_000L;
    private static final int TICK_MS = 50;
    private static final int LOBBY_TIMEOUT_TICKS = 20 * 10;
    /** Q3: the first press is answered, the hint counts it and the countdown shows within this. */
    private static final long QUEUED_MS = 2_000;
    /** Q4: the second press comes this long after the first, the third within {@link #THIRD_PRESS_MS} of it. */
    private static final long SECOND_PRESS_MS = 200;
    private static final long THIRD_PRESS_MS = 1_000;
    private static final int QUEUE_COOLDOWN_MS = 2_000;
    /** Q5: the client is in the arena this long after the countdown ran out. */
    private static final long ROUND_ENTER_MS = 5_000;
    private static final int READY_TICKS = 100;
    private static final int ROUND_MS = 45_000;
    private static final double SPAWN_X = 0.5;
    private static final double SPAWN_Z = 0.5;
    private static final double SPAWN_DISTANCE = 3;
    private static final List<String> BEACONS = List.of("A", "B", "C");
    private static final int LABEL_TICKS = 40;
    /** Q6: the middle of beacon A's block at (-12, 40, -12), and where to stop: 1.5 blocks from it towards the spawn. */
    private static final double BEACON_X = -11.5;
    private static final double BEACON_Z = -11.5;
    private static final double STOP_X = BEACON_X + 1.5 / Math.sqrt(2);
    private static final double STOP_Z = BEACON_Z + 1.5 / Math.sqrt(2);
    private static final double STOP_TOLERANCE = 0.4;
    private static final double BEACON_REACH = 2;
    private static final int WALK_TICKS = 20 * 15;
    private static final double STOPPED = 0.01;
    /** How far the player slides after the forward key is let go, per block per tick of speed (PlayableClientGameTest). */
    private static final double SLIDE = 1.3;
    private static final int CAPTURE_TICKS = 20 * 2;
    private static final int SCORE_MS = 13_000;
    /** Q7: how far the result may come from the moment the round TIMER ran out. */
    private static final long EXTRAPOLATION_MS = 500;
    private static final int ROUND_END_TICKS = 20 * 60;
    private static final int BACK_TICKS = 20 * 5;

    private final Map<String, String> lines = new LinkedHashMap<>();
    private @Nullable ClientGameTestContext context;

    @Override
    public void runTest(ClientGameTestContext context) {
        final String rush = env(RUSH_VARIABLE);
        final String fixture = env(FIXTURE_VARIABLE);
        if (rush.isBlank() && fixture.isBlank()) return;
        this.context = context;
        String ended = "the script ended before this step";
        try {
            context.waitForScreen(TitleScreen.class);
            if (fixture.isBlank()) {
                skip(STEPS.subList(0, 3), FIXTURE_VARIABLE + " not set");
            } else {
                q0(fixture);
                leave();
            }
            if (rush.isBlank()) skip(STEPS.subList(3, STEPS.size()), RUSH_VARIABLE + " not set");
            else rush(rush);
        } catch (RuntimeException | AssertionError e) {
            ended = "the script threw " + e;
        } finally {
            try {
                leave();
            } catch (RuntimeException | AssertionError e) {
                ended += "; leaving to the title screen threw " + e;
            }
            write(ended);
        }
    }

    // ---- Q0

    private void q0(String server) {
        if (!connect(server, "play fixture")) {
            final String why = "not in the world of " + server + ", screen " + ctx().computeOnClient(RushClientGameTest::screen);
            line("Q0a", false, why);
            line("Q0b", false, why);
            line("Q0c", false, why);
            return;
        }
        q0a();
        q0b();
        q0c();
    }

    /** Q0a: decoded as sent, drawn from the language file or the fallback, both colours on the screenshot. */
    private void q0a() {
        final boolean arrived = waitUntil(_ -> PlayTestHook.scene() != null && PlayTestHook.texts().size() >= WIDGETS.size(),
                HUD_TIMEOUT_TICKS);
        final boolean settled = waitUntil(client -> client.gui.screen() == null && underfoot(client), HUD_TIMEOUT_TICKS);
        ctx().waitTicks(FRAME_TICKS);
        final Path shot = ctx().takeScreenshot("play-text");
        final PlayTestHook.SceneSeen scene = ctx().computeOnClient(_ -> PlayTestHook.scene());
        final List<PlayTestHook.TextSeen> texts = ctx().computeOnClient(_ -> PlayTestHook.texts());
        final boolean hasHint = ctx().computeOnClient(_ -> Language.getInstance().has(HINT_KEY));
        final boolean hasMissing = ctx().computeOnClient(_ -> Language.getInstance().has(MISSING_KEY));
        final String translation = ctx().computeOnClient(_ -> Language.getInstance().getOrDefault(HINT_KEY))
                .replace("%s", HINT_ARGUMENT);

        final List<String> problems = new ArrayList<>();
        if (!arrived) problems.add("the scene and three TEXTs did not arrive in " + HUD_TIMEOUT_TICKS + " ticks");
        if (!settled) problems.add("the world was not loaded under the player in " + HUD_TIMEOUT_TICKS + " ticks");
        if (scene == null) {
            problems.add("no scene");
        } else {
            if (scene.seq() != 1 || !scene.id().equals(SCENE_ID)) problems.add("scene " + scene.seq() + " " + scene.id());
            if (!translatable(scene.title(), TITLE_KEY, List.of(), null, null)) problems.add("title " + describe(scene.title()));
        }
        if (!texts.stream().map(PlayTestHook.TextSeen::widgetId).toList().equals(WIDGETS)) {
            problems.add("TEXTs " + texts.stream().map(PlayTestHook.TextSeen::widgetId).toList() + ", not " + WIDGETS);
        } else {
            final PlayTestHook.TextSeen hint = texts.get(0), missing = texts.get(1), empty = texts.get(2);
            if (!translatable(hint.text(), HINT_KEY, List.of(HINT_ARGUMENT), HINT_COLOUR, HINT_FALLBACK)) {
                problems.add("hint decoded as " + describe(hint.text()));
            }
            if (!translatable(missing.text(), MISSING_KEY, List.of(), MISSING_COLOUR, MISSING_FALLBACK)) {
                problems.add("missing decoded as " + describe(missing.text()));
            }
            if (!hasHint || hasMissing) problems.add("language has " + HINT_KEY + "=" + hasHint + " " + MISSING_KEY + "=" + hasMissing);
            if (!hint.drawn().equals(translation)) problems.add("hint drawn \"" + hint.drawn() + "\", not \"" + translation + "\"");
            if (!missing.drawn().equals(MISSING_FALLBACK)) problems.add("missing drawn \"" + missing.drawn() + "\", not the fallback");
            if (!empty.drawn().isEmpty() || !empty.text().getSiblings().isEmpty()) problems.add("empty drawn \"" + empty.drawn() + "\"");
        }
        final BufferedImage image = read(shot);
        final Pixels gold = Pixels.near(image, HINT_COLOUR), green = Pixels.near(image, MISSING_COLOUR);
        if (gold.count() < MIN_PIXELS) problems.add("the screenshot has " + gold + " of " + hex(HINT_COLOUR));
        if (green.count() < MIN_PIXELS) problems.add("the screenshot has " + green + " of " + hex(MISSING_COLOUR));
        final boolean connected = ctx().computeOnClient(RushClientGameTest::inWorld);
        if (!connected) problems.add("the client left the world");

        line("Q0a", problems.isEmpty(), (problems.isEmpty() ? "" : "problems " + problems + "; ")
                + "scene " + (scene == null ? "none" : scene.seq() + " " + scene.id() + " title " + describe(scene.title()))
                + "; texts " + texts.stream().map(text -> text.widgetId() + " " + describe(text.text()) + " drawn \"" + text.drawn() + "\"")
                .toList()
                + "; language has " + HINT_KEY + "=" + hasHint + " " + MISSING_KEY + "=" + hasMissing
                + "; screenshot " + shot.getFileName() + " " + image.getWidth() + "x" + image.getHeight()
                + " " + hex(HINT_COLOUR) + " " + gold + " " + hex(MISSING_COLOUR) + " " + green);
    }

    /** Q0b: the screens, the dimension and the chunk underfoot of every tick from the request to the new level. */
    private void q0b() {
        PlayTestHook.ANSWERS.clear();
        final long requested = ctx().computeOnClient(_ -> {
            PlayTestHook.startSampling();
            ClientPlayNetworking.send(new PlayTestHook.Payload("move".getBytes(StandardCharsets.UTF_8)));
            return System.nanoTime();
        });
        final boolean ready = waitUntil(client -> client.level != null
                && client.level.dimension().identifier().toString().equals(ELSEWHERE)
                && client.gui.screen() == null && underfoot(client), MOVE_TIMEOUT_TICKS);
        final List<PlayTestHook.Sample> samples = ctx().computeOnClient(_ -> PlayTestHook.stopSampling());
        final String answer = answer();
        final int texts = ctx().computeOnClient(_ -> PlayTestHook.texts().size());

        final Map<String, int[]> screens = new LinkedHashMap<>();
        int moved = -1, closed = -1, readyTick = -1, lastScreen = -1;
        long readyNanos = 0;
        for (PlayTestHook.Sample sample : samples) {
            if (sample.screen() != null) {
                screens.computeIfAbsent(sample.screen(), _ -> new int[]{sample.tick(), sample.tick()})[1] = sample.tick();
                lastScreen = sample.tick();
            }
            final boolean there = ELSEWHERE.equals(sample.dimension());
            if (there && moved < 0) moved = sample.tick();
            if (there && sample.screen() == null && sample.hasChunk() && readyTick < 0) {
                readyTick = sample.tick();
                readyNanos = sample.nanos();
            }
        }
        for (PlayTestHook.Sample sample : samples) {
            if (lastScreen > 0 && sample.tick() > lastScreen && sample.screen() == null) {
                closed = sample.tick();
                break;
            }
        }
        final List<String> seen = new ArrayList<>();
        screens.forEach((name, ticks) -> seen.add(name + " ticks " + ticks[0] + ".." + ticks[1]));
        final boolean pass = ready && readyTick > 0;
        line("Q0b", pass, "requested at tick 0, " + samples.size() + " ticks sampled; dimension " + ELSEWHERE
                + (moved > 0 ? " from tick " + moved : " never") + "; screens " + seen
                + "; last screen closed " + (closed > 0 ? "at tick " + closed : lastScreen > 0 ? "never" : "(no screen)")
                + "; hasChunk " + runs(samples)
                + "; ready " + (readyTick > 0 ? "at tick " + readyTick + " after "
                + String.format(Locale.ROOT, "%.0f", (readyNanos - requested) / 1e6) + " ms" : "never")
                + "; answer \"" + answer + "\"; TEXTs after the move " + texts);
    }

    /**
     * Q0c: the second scene's widgets on the real client. Every reading goes into the evidence; any that misses
     * its mark adds a problem.
     */
    private void q0c() {
        final List<String> problems = new ArrayList<>();
        final List<String> evidence = new ArrayList<>();
        ctx().runOnClient(_ -> PlayTestHook.startRecording());
        final String widgets = request("widgets");
        evidence.add("answer \"" + widgets + "\"");

        // The panel opens on its own, over a world that keeps running
        final boolean opened = waitUntil(client -> client.gui.screen() instanceof PanelScreen, PANEL_TIMEOUT_TICKS);
        final String scene = ctx().computeOnClient(_ -> {
            final PlayTestHook.SceneSeen seen = PlayTestHook.scene();
            return seen == null ? "none" : seen.seq() + " " + seen.id();
        });
        final boolean pauses = ctx().computeOnClient(client -> client.gui.screen() != null && client.gui.screen().isPauseScreen());
        evidence.add("scene " + scene + "; panel " + (opened ? "open" : "not open") + " pause=" + pauses
                + "; widgets " + ctx().computeOnClient(_ -> PlayTestHook.widgets()));
        if (!scene.equals("2 " + WIDGETS_SCENE_ID)) problems.add("scene " + scene);
        if (!opened) problems.add("no PanelScreen in " + PANEL_TIMEOUT_TICKS + " ticks");
        if (pauses) problems.add("the panel pauses the game");
        clearToasts();
        final Path panelShot = ctx().takeScreenshot("play-panel");
        evidence.add("screenshot " + panelShot.getFileName());

        // Esc closes it here only: the mirror still holds it
        ctx().getInput().pressKey(InputConstants.KEY_ESCAPE);
        final boolean closed = waitUntil(client -> client.gui.screen() == null, RESULT_TIMEOUT_TICKS);
        final boolean held = ctx().computeOnClient(_ -> PlayTestHook.holdsPanel());
        evidence.add("Esc: screen " + (closed ? "closed" : "still open") + ", panel in the mirror " + held);
        if (!closed) problems.add("Esc left the panel open");
        if (!held) problems.add("Esc took the panel out of the mirror");

        // Each widget's colour in its slot's part of the screen; the timer counts down
        clearToasts();
        final long before = ctx().computeOnClient(_ -> PlayTestHook.timerLeft("timer"));
        final Path shot = ctx().takeScreenshot("play-widgets");
        final int[] gui = ctx().computeOnClient(client -> new int[]{client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight()});
        final BufferedImage image = read(shot);
        final double scale = (double) image.getWidth() / gui[0];
        final int w = image.getWidth(), h = image.getHeight();
        final Pixels timer = Pixels.near(image, TIMER_COLOUR), score = Pixels.near(image, SCORE_COLOUR),
                values = Pixels.near(image, VALUE_COLOUR), capture = Pixels.near(image, CAPTURE_COLOUR);
        evidence.add("screenshot " + shot.getFileName() + " " + w + "x" + h + " gui " + gui[0] + "x" + gui[1]
                + "; TIMER " + hex(TIMER_COLOUR) + " " + timer + "; SCOREBOARD title " + hex(SCORE_COLOUR) + " " + score
                + " values " + hex(VALUE_COLOUR) + " " + values + "; BOTTOM TEXT " + hex(CAPTURE_COLOUR) + " " + capture
                + " (action bar starts at y " + Math.round((gui[1] - ACTION_BAR_TOP) * scale) + ")");
        if (timer.count() < MIN_PIXELS || timer.x0() < w / 4 || timer.x1() > w * 3 / 4 || timer.y1() > h / 4) {
            problems.add("the TIMER is not at the top centre");
        }
        if (score.count() < MIN_PIXELS || score.x0() < w / 2 || score.y1() > h / 3) problems.add("the SCOREBOARD title is not at the top right");
        if (values.count() < MIN_PIXELS || values.x0() < w / 2 || values.y1() > h / 3) problems.add("the SCOREBOARD values are not at the top right");
        if (capture.count() < MIN_PIXELS || capture.x0() < w / 4 || capture.x1() > w * 3 / 4 || capture.y0() < h / 2
                || capture.y1() > (gui[1] - ACTION_BAR_TOP) * scale) {
            problems.add("the BOTTOM TEXT is not centred above the action bar");
        }
        ctx().waitTicks(20);
        final long after = ctx().computeOnClient(_ -> PlayTestHook.timerLeft("timer"));
        evidence.add("TIMER shows " + before + " ms, a second later " + after + " ms");
        if (before <= 0 || after >= before) problems.add("the TIMER does not count down");

        // The action key: sent once, held back inside the local cooldown, answered by the server's with it off
        final int sentBefore = ctx().computeOnClient(_ -> PlayTestHook.actionsSent());
        ctx().getInput().pressKey(InputConstants.KEY_H);
        final PlayClient.Result first = result(1);
        final int sentFirst = ctx().computeOnClient(_ -> PlayTestHook.actionsSent());
        ctx().getInput().pressKey(InputConstants.KEY_H);
        ctx().waitTicks(2);
        final int sentSecond = ctx().computeOnClient(_ -> PlayTestHook.actionsSent());
        ctx().runOnClient(_ -> PlayTestHook.localCooldown(false));
        ctx().getInput().pressKey(InputConstants.KEY_H);
        final PlayClient.Result third = result(2);
        ctx().runOnClient(_ -> PlayTestHook.localCooldown(true));
        evidence.add("action key: sent " + sentBefore + " -> " + sentFirst + " -> " + sentSecond + " (inside the local cooldown)"
                + "; first " + describe(first) + "; with the local cooldown off " + describe(third));
        if (sentFirst != sentBefore + 1 || first == null || first.code() != ActionResultCode.OK) problems.add("the first press did not come back ok");
        if (sentSecond != sentFirst) problems.add("the local cooldown did not hold the second press back");
        if (third == null || third.code() != ActionResultCode.COOLDOWN || third.cooldownMs() < 1 || third.cooldownMs() > POKE_COOLDOWN_MS) {
            problems.add("the third press was not answered with the server's cooldown");
        }

        // The panel again: a refused button keeps it with the reason, the accepted one closes it
        final String again = request("panel");
        final boolean reopened = waitUntil(client -> client.gui.screen() instanceof PanelScreen, PANEL_TIMEOUT_TICKS);
        final String refuse = press(REFUSE_BUTTON);
        final PlayClient.Result refused = result(3);
        ctx().waitTicks(2);
        final boolean stays = ctx().computeOnClient(client -> client.gui.screen() instanceof PanelScreen);
        final Component reason = ctx().computeOnClient(_ -> PlayTestHook.panelReason());
        final String close = press(CLOSE_BUTTON);
        final boolean gone = waitUntil(client -> client.gui.screen() == null, RESULT_TIMEOUT_TICKS);
        final boolean emptied = !ctx().computeOnClient(_ -> PlayTestHook.holdsPanel());
        evidence.add("panel again: answer \"" + again + "\" " + (reopened ? "open" : "not open") + "; " + refuse + " -> " + describe(refused)
                + ", panel " + (stays ? "stays" : "closed") + " reason " + (reason == null ? "none" : describe(reason))
                + "; " + close + " -> panel " + (gone ? "closed" : "open") + ", in the mirror " + !emptied);
        if (!reopened) problems.add("the panel's next SET did not open it again");
        if (refused == null || refused.code() != ActionResultCode.REJECTED || !stays || reason == null) {
            problems.add("the refused button did not keep the panel with its reason");
        }
        if (!gone || !emptied) problems.add("the accepted button did not close the panel");

        // One C2S_SCENE_READY per scene, the second sent with no screen open and the chunk underfoot
        final String readies = request("ready?");
        final List<SceneReadyProbe.Sent> sent = ctx().computeOnClient(_ -> List.copyOf(PlayTestHook.recording().readies));
        ctx().runOnClient(_ -> PlayTestHook.stopRecording());
        evidence.add("fixture \"" + readies + "\"; probe sent " + sent);
        if (!readies.equals("ready 1 2")) problems.add("the fixture got the readies \"" + readies + "\"");
        final SceneReadyProbe.Sent second = sent.stream().filter(ready -> ready.sceneSeq() == 2).findFirst().orElse(null);
        if (second == null || second.screen() != null || !second.chunkUnderfoot()) problems.add("scene 2's ready was not sent in a loaded level");
        // Scene 1 was answered before the recording began
        if (sent.size() != 1) problems.add("the recording holds " + sent.size() + " readies, not scene 2's alone");

        // A recording started now holds nothing the connection had before it
        ctx().runOnClient(_ -> PlayTestHook.startRecording());
        ctx().waitTicks(2);
        final String carried = ctx().computeOnClient(_ -> {
            final PlayTestHook.Recording late = PlayTestHook.recording();
            return late.sets.size() + " SETs, " + late.results.size() + " results, " + late.readies.size() + " readies";
        });
        ctx().runOnClient(_ -> PlayTestHook.stopRecording());
        evidence.add("a recording started after all this holds " + carried);
        if (!carried.equals("0 SETs, 0 results, 0 readies")) problems.add("a recording started late holds " + carried + " from before it");

        line("Q0c", problems.isEmpty(), (problems.isEmpty() ? "" : "problems " + problems + "; ") + String.join("; ", evidence));
    }

    // ---- Q1 to Q9

    /** Q1 to Q9 against the sample server: one round played alone, from the lobby back to the lobby. */
    private void rush(String server) {
        final List<String> rest = STEPS.subList(3, STEPS.size());
        if (!connect(server, "rush")) {
            notReached(rest, "not in the world of " + server + ", screen " + ctx().computeOnClient(RushClientGameTest::screen));
            return;
        }
        q1();
        if (!q2()) {
            notReached(STEPS.subList(STEPS.indexOf("Q3"), STEPS.size()), "Q2 found no lobby");
            return;
        }
        // From here on the recording holds this connection's SETs, results and readies (§6.4.8)
        ctx().runOnClient(_ -> PlayTestHook.startRecording());
        final Long countdownEnd = q3q4();
        if (countdownEnd == null) {
            notReached(STEPS.subList(STEPS.indexOf("Q5"), STEPS.size()), "Q3 did not queue the player");
            return;
        }
        if (!q5(countdownEnd)) {
            notReached(STEPS.subList(STEPS.indexOf("Q6"), STEPS.size()), "Q5 found no round");
            return;
        }
        q6();
        if (!q7()) {
            notReached(STEPS.subList(STEPS.indexOf("Q8"), STEPS.size()), "Q7 saw no result");
            return;
        }
        final Long pressed = q8();
        if (pressed == null) {
            notReached(List.of("Q9"), "Q8 pressed no button back to the lobby");
            return;
        }
        q9(pressed);
    }

    private boolean connect(String server, String name) {
        ctx().runOnClient(client -> ConnectScreen.startConnecting(new TitleScreen(), client, ServerAddress.parseString(server),
                new ServerData(name, server, ServerData.Type.OTHER), false, null));
        return waitUntil(client -> inWorld(client) || client.gui.screen() instanceof DisconnectedScreen, CONNECT_TIMEOUT_TICKS)
                && ctx().computeOnClient(RushClientGameTest::inWorld);
    }

    /** What Q1 reads of the controls: action key 1 among them, the mappings on its default key. */
    private record Keys(boolean listed, String key, List<String> clashes, List<String> debug) {
    }

    /** Q1: the handshake, the payload of synaxis:play, its version and action key 1's default key to itself. */
    private void q1() {
        final ClientHandshake.State state = ctx().computeOnClient(_ -> PlayTestHook.handshake().state());
        final boolean registered = ctx().computeOnClient(_ -> ClientPlayNetworking.getGlobalReceivers().contains(PlayPayload.TYPE.id()));
        final int version = PlayProtocol.version();
        final Keys keys = ctx().computeOnClient(client -> {
            final InputConstants.Key key = ActionKeys.KEY_1.getDefaultKey();
            final List<String> clashes = new ArrayList<>(), debug = new ArrayList<>();
            for (final KeyMapping mapping : client.options.keyMappings) {
                if (mapping == ActionKeys.KEY_1 || !mapping.getDefaultKey().equals(key)) continue;
                // F3+H and the other F3 combinations only fire with F3 held (§6.4.8)
                (mapping.getCategory().equals(KeyMapping.Category.DEBUG) ? debug : clashes).add(mapping.getName());
            }
            return new Keys(Arrays.asList(client.options.keyMappings).contains(ActionKeys.KEY_1), key.getName(), clashes, debug);
        });
        line("Q1", state == ClientHandshake.State.VERIFIED && registered && version == 1 && keys.listed() && keys.clashes().isEmpty(),
                "handshake " + state + "; " + PlayProtocol.CHANNEL + " payload registered " + registered + "; play_version " + version
                        + "; " + ActionKeys.ACTION_1 + " in the controls " + keys.listed() + ", default " + keys.key()
                        + ", other mappings on it " + keys.clashes() + " (DEBUG left out: " + keys.debug() + ")");
    }

    /** Q2: the lobby scene, its hint and its queue action. */
    private boolean q2() {
        final boolean lobby = waitUntil(_ -> inScene(LOBBY_SCENE) && entry(HINT) != null, LOBBY_TIMEOUT_TICKS);
        final String scene = ctx().computeOnClient(_ -> sceneSeen());
        final String queue = ctx().computeOnClient(_ -> {
            final PlayClient.Scene seen = PlayTestHook.play().scene();
            final PlayClient.Action action = seen == null ? null : seen.action(0);
            return action == null ? "none" : action.actionId();
        });
        final String hint = ctx().computeOnClient(_ -> describe(entry(HINT)));
        clearToasts();
        final Path shot = ctx().takeScreenshot("rush-lobby");
        line("Q2", lobby && queue.equals(QUEUE_ACTION), "scene " + scene + " within " + LOBBY_TIMEOUT_TICKS + " ticks " + lobby
                + "; slot 0 " + queue + "; hint " + hint + "; screenshot " + shot.getFileName());
        return lobby;
    }

    /**
     * Q3 and Q4, one press at a time: the recording keeps one result a tick, so each press waits for its own. Q3 is
     * judged before the second press, so that what the later presses do to the queue does not reach it.
     *
     * @return when the countdown runs out, {@link System#nanoTime} of the client, null when the first press queued
     * nobody
     */
    private @Nullable Long q3q4() {
        final int before = ctx().computeOnClient(_ -> PlayTestHook.actionsSent());
        final long pressed = System.nanoTime();
        ctx().getInput().pressKey(ActionKeys.KEY_1);
        final PlayClient.Result first = result(1);
        final int afterFirst = ctx().computeOnClient(_ -> PlayTestHook.actionsSent());

        // Q3: by 2 s after the first press the hint counts one queued and the countdown shows
        final long waited = (System.nanoTime() - pressed) / NANOS_PER_MS;
        // The recording takes a SET in at the end of the tick it came in, which may be after this check sees the mirror
        final boolean queued = waitUntil(_ -> "1".equals(argument(entry(HINT), 0)) && recorded(TIMER) != null,
                (int) Math.max(1, (QUEUED_MS - waited) / TICK_MS));
        final String count = ctx().computeOnClient(_ -> argument(entry(HINT), 0));
        final PlayTestHook.SetSeen countdown = ctx().computeOnClient(_ -> recorded(TIMER));
        final boolean ok = first != null && first.code() == ActionResultCode.OK
                && first.receivedNanos() - pressed <= QUEUED_MS * NANOS_PER_MS;
        line("Q3", ok && queued && countdown != null, "first press " + describe(first) + " "
                + (first == null ? "" : ms(first.receivedNanos() - pressed) + " ms after it") + "; actions sent " + before + " -> " + afterFirst
                + "; hint count " + count + "; countdown TIMER " + (countdown == null ? "none"
                : "set " + countdown.set() + " remaining_ms " + countdown.remainingMs()));

        // Q4: the second press inside the local cooldown, the third with it off
        while (System.nanoTime() - pressed < SECOND_PRESS_MS * NANOS_PER_MS) ctx().waitTick();
        final long second = System.nanoTime();
        ctx().getInput().pressKey(ActionKeys.KEY_1);
        ctx().waitTicks(2);
        final int afterSecond = ctx().computeOnClient(_ -> PlayTestHook.actionsSent());
        ctx().runOnClient(_ -> PlayTestHook.localCooldown(false));
        final long third = System.nanoTime();
        ctx().getInput().pressKey(ActionKeys.KEY_1);
        final PlayClient.Result answer = result(2);
        ctx().runOnClient(_ -> PlayTestHook.localCooldown(true));
        final long gap = (third - pressed) / NANOS_PER_MS;
        final boolean cooldown = answer != null && answer.code() == ActionResultCode.COOLDOWN
                && answer.cooldownMs() >= 1 && answer.cooldownMs() <= QUEUE_COOLDOWN_MS;
        final String still = ctx().computeOnClient(_ -> argument(entry(HINT), 0));
        line("Q4", afterFirst == before + 1 && afterSecond == afterFirst && gap <= THIRD_PRESS_MS && cooldown && "1".equals(still),
                "second press " + ms(second - pressed) + " ms after the first: actions sent " + afterFirst + " -> " + afterSecond
                        + " (the local cooldown); third press with the local cooldown off " + gap + " ms after the first: "
                        + describe(answer) + "; hint count " + still);
        return ok && countdown != null ? countdown.receivedNanos() + countdown.remainingMs() * NANOS_PER_MS : null;
    }

    /**
     * Q5: into the arena within 5 s of the countdown's end, ready there once the level is in with no screen open,
     * within 100 ticks; the sampling begins in the lobby, so that it holds the tick the new level came in.
     */
    private boolean q5(long countdownEnd) {
        ctx().runOnClient(_ -> PlayTestHook.startSampling());
        final long budget = (countdownEnd - System.nanoTime()) / NANOS_PER_MS + ROUND_ENTER_MS + 2 * TICK_MS;
        final boolean entered = waitUntil(client -> dimension(client).startsWith(ROUND_DIMENSION) && inScene(ROUND_SCENE)
                && timer(entry(TIMER)) != null, (int) Math.max(1, budget / TICK_MS));
        final int seq = ctx().computeOnClient(_ -> sceneSeq());
        final boolean answered = entered && waitUntil(_ -> readyFor(seq) != null, READY_TICKS + 20);
        final boolean labelled = entered && waitUntil(client -> letters(client).containsAll(BEACONS), LABEL_TICKS);
        final List<PlayTestHook.Sample> samples = ctx().computeOnClient(_ -> PlayTestHook.stopSampling());
        final SceneReadyProbe.Sent ready = ctx().computeOnClient(_ -> readyFor(seq));
        final String scene = ctx().computeOnClient(_ -> sceneSeen());
        final long timerMs = ctx().computeOnClient(_ -> timerLeft(TIMER));
        final double distance = ctx().computeOnClient(client -> Math.hypot(client.player.getX() - SPAWN_X, client.player.getZ() - SPAWN_Z));
        final Set<String> letters = ctx().computeOnClient(RushClientGameTest::letters);
        final String dimension = ctx().computeOnClient(RushClientGameTest::dimension);

        final PlayTestHook.Sample arrived = samples.stream().filter(sample -> sample.dimension() != null
                && sample.dimension().startsWith(ROUND_DIMENSION)).findFirst().orElse(null);
        final String began = samples.isEmpty() ? "no tick" : samples.getFirst().dimension();
        final List<String> problems = new ArrayList<>();
        if (began == null || !began.startsWith(LOBBY_DIMENSION)) problems.add("the sampling began in " + began + ", not in the lobby");
        if (!entered) problems.add("not in a round with its TIMER " + budget + " ms after the countdown ran out");
        final long late = arrived == null ? Long.MAX_VALUE : (arrived.nanos() - countdownEnd) / NANOS_PER_MS;
        if (late > ROUND_ENTER_MS) problems.add("the round's level came " + late + " ms after the countdown ran out");
        int ticks = -1;
        if (!answered || ready == null) {
            problems.add("no C2S_SCENE_READY for scene " + seq);
        } else {
            if (ready.screen() != null) problems.add("sent ready with " + ready.screen() + " open");
            if (!ready.chunkUnderfoot()) problems.add("sent ready with the chunk underfoot missing");
            if (arrived != null) {
                ticks = (int) samples.stream().filter(sample -> sample.tick() >= arrived.tick() && sample.nanos() < ready.nanos()).count();
                if (ticks > READY_TICKS) problems.add("ready " + ticks + " ticks after the new level");
            }
        }
        if (timerMs <= 0 || timerMs > ROUND_MS) problems.add("the TIMER shows " + timerMs + " ms");
        if (distance > SPAWN_DISTANCE) problems.add(String.format(Locale.ROOT, "%.2f blocks from the spawn", distance));
        if (!labelled) problems.add("the beacon labels are " + letters);
        line("Q5", problems.isEmpty(), (problems.isEmpty() ? "" : "problems " + problems + "; ")
                + "dimension " + dimension + " " + (late == Long.MAX_VALUE ? "never sampled" : late + " ms after the countdown ran out")
                + "; scene " + scene + "; screens " + screens(samples) + "; hasChunk " + runs(samples)
                + "; ready " + (ready == null ? "none" : "screen=" + ready.screen() + " chunkUnderfoot=" + ready.chunkUnderfoot()
                + " " + ticks + " ticks" + (arrived == null ? "" : " " + ms(ready.nanos() - arrived.nanos()) + " ms")
                + " after the new level")
                + "; TIMER " + timerMs + " ms; " + String.format(Locale.ROOT, "%.2f", distance) + " blocks from the spawn"
                + "; labels " + letters);
        return entered;
    }

    /** Q6: walks to beacon A and takes it. */
    private void q6() {
        final boolean reached = walkTo(STOP_X, STOP_Z, STOP_TOLERANCE, WALK_TICKS);
        final long arrived = System.nanoTime();
        final double distance = ctx().computeOnClient(client -> Math.hypot(client.player.getX() - BEACON_X, client.player.getZ() - BEACON_Z));
        final boolean shows = waitUntil(_ -> percent() >= 0, CAPTURE_TICKS);
        final int first = ctx().computeOnClient(_ -> percent());
        final long firstAt = System.nanoTime();
        clearToasts();
        final Path shot = ctx().takeScreenshot("rush-capture");
        while (System.nanoTime() - firstAt < CAPTURE_TICKS * TICK_MS * NANOS_PER_MS) ctx().waitTick();
        final int second = ctx().computeOnClient(_ -> percent());
        final long left = SCORE_MS - (System.nanoTime() - arrived) / NANOS_PER_MS;
        final boolean scored = waitUntil(_ -> ownScore() >= 1, (int) Math.max(1, left / TICK_MS));
        final long scoredAfter = (System.nanoTime() - arrived) / NANOS_PER_MS;
        final int score = ctx().computeOnClient(_ -> ownScore());
        line("Q6", reached && distance <= BEACON_REACH && shows && second > first && scored,
                String.format(Locale.ROOT, "stopped %.2f blocks from beacon A", distance) + " (reached " + reached
                        + "); rush/capture " + (shows ? first + "% then " + second + "% 2 s later" : "not within " + CAPTURE_TICKS + " ticks")
                        + "; own score " + score + (scored ? " at " + scoredAfter + " ms" : " after " + SCORE_MS + " ms")
                        + "; screenshot " + shot.getFileName());
    }

    /** Q7: the result against the moment the round's TIMER ran out, both from the recording. */
    private boolean q7() {
        final boolean ended = waitUntil(_ -> recorded(RESULT) != null, ROUND_END_TICKS);
        final List<PlayTestHook.SetSeen> sets = ctx().computeOnClient(_ -> List.copyOf(PlayTestHook.recording().sets));
        final PlayTestHook.SetSeen round = sets.stream().filter(set -> set.owner().equals(RUSH) && set.widgetId().equals(TIMER))
                .max(Comparator.comparingInt(PlayTestHook.SetSeen::remainingMs)).orElse(null);
        final PlayTestHook.SetSeen result = sets.stream().filter(set -> set.owner().equals(RUSH) && set.widgetId().equals(RESULT))
                .findFirst().orElse(null);
        if (round == null || result == null) {
            line("Q7", false, "the recording has the round TIMER " + round + " and the result " + result + " (" + sets.size() + " SETs)");
            return ended;
        }
        final long off = (result.receivedNanos() - (round.receivedNanos() + round.remainingMs() * NANOS_PER_MS)) / NANOS_PER_MS;
        line("Q7", Math.abs(off) <= EXTRAPOLATION_MS, "round TIMER set " + round.set() + " remaining_ms " + round.remainingMs()
                + "; rush/result set " + result.set() + " " + ms(result.receivedNanos() - round.receivedNanos())
                + " ms after it; off by " + off + " ms");
        return ended;
    }

    /**
     * Q8: the result panel with this player's row, and its button back to the lobby.
     *
     * @return when the button was pressed, null when it was not
     */
    private @Nullable Long q8() {
        final boolean open = waitUntil(client -> client.gui.screen() instanceof PanelScreen, PANEL_TIMEOUT_TICKS);
        final String name = ctx().computeOnClient(client -> client.player.getName().getString());
        final List<String> rows = ctx().computeOnClient(_ -> {
            final PlayClient.Entry panel = PlayTestHook.play().panel();
            if (panel == null || !(panel.content() instanceof PlayClient.Panel content)) return List.<String>of();
            return content.rows().stream().map(row -> (row.highlight() ? "*" : "") + row.label().getString() + "=" + row.value()).toList();
        });
        final boolean mine = rows.stream().anyMatch(row -> row.startsWith("*") && row.contains(name) && score(row) >= 1);
        clearToasts();
        final Path shot = ctx().takeScreenshot("rush-result");
        final long at = System.nanoTime();
        final String back = press(0);
        final boolean pressed = back.startsWith("pressed");
        line("Q8", open && mine && pressed, "PanelScreen " + (open ? "open" : "not open in " + PANEL_TIMEOUT_TICKS + " ticks")
                + "; rows " + rows + " (own row marked *, player " + name + "); screenshot " + shot.getFileName() + "; " + back);
        return pressed ? at : null;
    }

    /** Q9: back in the lobby within 5 s of the button, and ready there once the level is in with no screen open. */
    private void q9(long pressed) {
        final boolean back = waitUntil(client -> dimension(client).startsWith(LOBBY_DIMENSION) && inScene(LOBBY_SCENE), BACK_TICKS);
        final long took = (System.nanoTime() - pressed) / NANOS_PER_MS;
        final int seq = ctx().computeOnClient(_ -> sceneSeq());
        // The ledger's readiness after this move (the judge's moved-to-ready check) needs the ready sent
        final boolean answered = back && waitUntil(_ -> readyFor(seq) != null, READY_TICKS + 20);
        final SceneReadyProbe.Sent ready = ctx().computeOnClient(_ -> readyFor(seq));
        ctx().runOnClient(_ -> PlayTestHook.stopRecording());
        final boolean loaded = answered && ready != null && ready.screen() == null && ready.chunkUnderfoot();
        line("Q9", back && loaded, "dimension " + ctx().computeOnClient(RushClientGameTest::dimension) + ", scene "
                + ctx().computeOnClient(_ -> sceneSeen()) + (back ? " " + took + " ms after the button" : " not within " + BACK_TICKS + " ticks")
                + "; ready " + (answered && ready != null ? "screen=" + ready.screen() + " chunkUnderfoot=" + ready.chunkUnderfoot() : "none"));
    }

    /**
     * Walks towards (x, z) with the forward key, turning towards it every tick and letting go of the key while the
     * slide would carry the player there (PlayableClientGameTest#walkTo).
     *
     * @return whether the player stands within the tolerance
     */
    private boolean walkTo(double x, double z, double tolerance, int ticks) {
        boolean holding = false;
        try {
            for (int tick = 0; tick < ticks; tick++) {
                final double[] state = ctx().computeOnClient(client -> new double[] {client.player.getX(), client.player.getZ(),
                        client.player.getDeltaMovement().horizontalDistance()});
                final double dx = x - state[0], dz = z - state[1];
                final double distance = Math.hypot(dx, dz);
                if (distance <= tolerance && state[2] < STOPPED) return true;
                final boolean hold = distance > tolerance / 2 + state[2] * SLIDE;
                if (distance > tolerance / 2) ctx().getInput().lookAt((float) Math.toDegrees(Math.atan2(-dx, dz)), 0f);
                if (hold != holding) {
                    if (hold) ctx().getInput().holdKey(options -> options.keyUp);
                    else ctx().getInput().releaseKey(options -> options.keyUp);
                    holding = hold;
                }
                ctx().waitTick();
            }
            return false;
        } finally {
            if (holding) ctx().getInput().releaseKey(options -> options.keyUp);
        }
    }

    private void notReached(List<String> steps, String why) {
        for (String step : steps) line(step, false, "not reached: " + why);
    }

    // What the mirror holds; on the client thread

    private static boolean inScene(String id) {
        final PlayClient.Scene scene = PlayTestHook.play().scene();
        return scene != null && scene.id().equals(id);
    }

    private static int sceneSeq() {
        final PlayClient.Scene scene = PlayTestHook.play().scene();
        return scene == null ? -1 : scene.seq();
    }

    private static String sceneSeen() {
        final PlayClient.Scene scene = PlayTestHook.play().scene();
        return scene == null ? "none" : scene.seq() + " " + scene.id();
    }

    private static PlayClient.@Nullable Entry entry(String widgetId) {
        return PlayTestHook.play().widgets().stream().filter(entry -> entry.owner().equals(RUSH) && entry.widgetId().equals(widgetId))
                .findFirst().orElse(null);
    }

    private static PlayClient.@Nullable Timer timer(PlayClient.@Nullable Entry entry) {
        return entry != null && entry.content() instanceof PlayClient.Timer timer ? timer : null;
    }

    private static long timerLeft(String widgetId) {
        final PlayClient.Entry entry = entry(widgetId);
        return timer(entry) == null ? -1 : PlayTestHook.play().remainingMs(entry);
    }

    /** One argument of a {@code TEXT}'s translatable component, null when there is no such text or argument. */
    private static @Nullable String argument(PlayClient.@Nullable Entry entry, int index) {
        if (entry == null || !(entry.content() instanceof PlayClient.Text text)
                || !(text.text().getContents() instanceof TranslatableContents contents)) return null;
        final List<String> arguments = arguments(contents);
        return index < arguments.size() ? arguments.get(index) : null;
    }

    /** The percentage {@code rush/capture} shows, -1 when it is not there. */
    private static int percent() {
        final String percent = argument(entry(CAPTURE), 1);
        return percent == null ? -1 : score(percent);
    }

    /** This player's score on {@code rush/score}, the highlighted row; -1 when there is none. */
    private static int ownScore() {
        final PlayClient.Entry entry = entry(SCORE);
        if (entry == null || !(entry.content() instanceof PlayClient.Scoreboard board)) return -1;
        return board.rows().stream().filter(PlayClient.Row::highlight).findFirst().map(row -> score(row.value())).orElse(-1);
    }

    /** The number after the last {@code =} of a text, or the whole text's; -1 when it is not one. */
    private static int score(String text) {
        final int at = text.lastIndexOf('=');
        try {
            return Integer.parseInt(text.substring(at + 1).strip());
        } catch (NumberFormatException _) {
            return -1;
        }
    }

    /** The first SET of one of rush's widgets in the recording, null before it is there. */
    private static PlayTestHook.@Nullable SetSeen recorded(String widgetId) {
        return PlayTestHook.recording().sets.stream().filter(set -> set.owner().equals(RUSH) && set.widgetId().equals(widgetId))
                .findFirst().orElse(null);
    }

    private static SceneReadyProbe.@Nullable Sent readyFor(int sceneSeq) {
        return PlayTestHook.recording().readies.stream().filter(ready -> ready.sceneSeq() == sceneSeq).findFirst().orElse(null);
    }

    private static String describe(PlayClient.@Nullable Entry entry) {
        if (entry == null) return "none";
        return switch (entry.content()) {
            case PlayClient.Text text -> describe(text.text());
            case PlayClient.Timer timer -> "TIMER " + describe(timer.label()) + " remaining_ms " + timer.remainingMs();
            case PlayClient.Scoreboard board -> "SCOREBOARD " + board.rows().size() + " rows";
            case PlayClient.Panel panel -> "PANEL " + panel.rows().size() + " rows";
        };
    }

    /** The first letter of every text display in the level: the beacon labels of a round. */
    private static Set<String> letters(Minecraft client) {
        final Set<String> letters = new TreeSet<>();
        if (client.level == null) return letters;
        for (final Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof Display.TextDisplay display) || display.textRenderState() == null) continue;
            final String text = display.textRenderState().text().getString();
            if (!text.isEmpty()) letters.add(text.substring(0, 1));
        }
        return letters;
    }

    private static String dimension(Minecraft client) {
        return client.level == null ? "" : client.level.dimension().identifier().toString();
    }

    /** The screens of a sampling with the ticks each was open, {@code [Name ticks 3..9]}. */
    private static List<String> screens(List<PlayTestHook.Sample> samples) {
        final Map<String, int[]> screens = new LinkedHashMap<>();
        for (PlayTestHook.Sample sample : samples) {
            if (sample.screen() != null) screens.computeIfAbsent(sample.screen(), _ -> new int[]{sample.tick(), sample.tick()})[1] = sample.tick();
        }
        final List<String> seen = new ArrayList<>();
        screens.forEach((name, ticks) -> seen.add(name + " ticks " + ticks[0] + ".." + ticks[1]));
        return seen;
    }

    private static long ms(long nanos) {
        return nanos / NANOS_PER_MS;
    }

    /** Sends a request on the test channel and waits for the fixture's answer. */
    private String request(String text) {
        PlayTestHook.ANSWERS.clear();
        ctx().runOnClient(_ -> ClientPlayNetworking.send(new PlayTestHook.Payload(text.getBytes(StandardCharsets.UTF_8))));
        return answer();
    }

    /** The {@code n}th action result of the recording, waited for; null when it did not come. */
    private PlayClient.@Nullable Result result(int n) {
        final boolean came = waitUntil(_ -> PlayTestHook.recording().results.size() >= n, RESULT_TIMEOUT_TICKS);
        return came ? ctx().computeOnClient(_ -> PlayTestHook.recording().results.get(n - 1)) : null;
    }

    /** Presses one button of the open panel the way a click does; what was pressed, or why nothing was. */
    private String press(int index) {
        return ctx().computeOnClient(client -> {
            if (!(client.gui.screen() instanceof PanelScreen screen)) return "no panel to press button " + index + " on";
            final List<Button> buttons = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast).toList();
            if (index >= buttons.size()) return "the panel has " + buttons.size() + " buttons, not " + (index + 1);
            final Button button = buttons.get(index);
            button.onPress(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
            return "pressed \"" + button.getMessage().getString() + "\"";
        });
    }

    private void clearToasts() {
        // The Social Interactions toast covers the top right corner after joining (TC-PLAY-020 section)
        ctx().runOnClient(client -> client.gui.toastManager().clear());
        ctx().waitTicks(FRAME_TICKS);
    }

    private static String describe(PlayClient.@Nullable Result result) {
        return result == null ? "no result in " + RESULT_TIMEOUT_TICKS + " ticks"
                : "action_seq " + result.actionSeq() + " " + result.code() + " cooldown_ms " + result.cooldownMs()
                + (result.reason().getString().isEmpty() ? "" : " reason " + describe(result.reason()));
    }

    /** The answer of the fixture, or why there is none. */
    private String answer() {
        for (int i = 0; i < ANSWER_TIMEOUT_TICKS; i++) {
            final byte[] answer = PlayTestHook.ANSWERS.poll();
            if (answer != null) return new String(answer, StandardCharsets.UTF_8);
            ctx().waitTick();
        }
        return "none in " + ANSWER_TIMEOUT_TICKS + " ticks";
    }

    /** {@code hasChunk} per tick as runs, {@code T1-3 F4-9 T10-12}, with the dimension where it changes. */
    private static String runs(List<PlayTestHook.Sample> samples) {
        final StringBuilder out = new StringBuilder();
        PlayTestHook.Sample first = null, last = null;
        for (PlayTestHook.Sample sample : samples) {
            if (first != null && (sample.hasChunk() != first.hasChunk() || !Objects.equals(sample.dimension(), first.dimension()))) {
                out.append(run(first, last)).append(' ');
                first = null;
            }
            if (first == null) first = sample;
            last = sample;
        }
        if (first != null) out.append(run(first, last));
        return out.toString().trim();
    }

    private static String run(PlayTestHook.Sample first, PlayTestHook.Sample last) {
        return (first.hasChunk() ? "T" : "F") + first.tick() + "-" + last.tick() + "@" + first.dimension();
    }

    // ---- What a component decoded into

    private static boolean translatable(Component component, String key, List<String> arguments, @Nullable Integer colour,
                                        @Nullable String fallback) {
        if (!(component.getContents() instanceof TranslatableContents contents)) return false;
        final TextColor color = component.getStyle().getColor();
        return contents.getKey().equals(key) && arguments(contents).equals(arguments)
                && Objects.equals(colour, color == null ? null : color.getValue())
                && Objects.equals(fallback, contents.getFallback());
    }

    private static List<String> arguments(TranslatableContents contents) {
        return Arrays.stream(contents.getArgs()).map(arg -> arg instanceof Component c ? c.getString() : String.valueOf(arg)).toList();
    }

    private static String describe(Component component) {
        final TextColor color = component.getStyle().getColor();
        final String colour = color == null ? "none" : hex(color.getValue());
        if (component.getContents() instanceof TranslatableContents contents) {
            return "key=" + contents.getKey() + " args=" + arguments(contents) + " colour=" + colour
                    + " fallback=" + contents.getFallback();
        }
        return "text=\"" + component.getString() + "\" colour=" + colour;
    }

    // ---- The screenshot

    /** How many pixels of an image are near one colour, and the box they lie in. */
    private record Pixels(int count, int x0, int y0, int x1, int y1) {
        static Pixels near(BufferedImage image, int rgb) {
            int count = 0, x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    final int p = image.getRGB(x, y);
                    final int distance = Math.max(Math.abs((p >> 16 & 255) - (rgb >> 16 & 255)),
                            Math.max(Math.abs((p >> 8 & 255) - (rgb >> 8 & 255)), Math.abs((p & 255) - (rgb & 255))));
                    if (distance > NEAR) continue;
                    count++;
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                }
            }
            return new Pixels(count, x0, y0, x1, y1);
        }

        @Override
        public String toString() {
            return count + " px" + (count > 0 ? " in " + x0 + "," + y0 + ".." + x1 + "," + y1 : "");
        }
    }

    private static BufferedImage read(Path path) {
        try {
            return Objects.requireNonNull(ImageIO.read(path.toFile()), path.toString());
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + path, e);
        }
    }

    private static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06x", rgb & 0xFFFFFF);
    }

    // ---- The client

    private static boolean inWorld(Minecraft client) {
        return client.level != null && client.player != null;
    }

    private static boolean underfoot(Minecraft client) {
        return inWorld(client) && client.level.hasChunk(client.player.blockPosition().getX() >> 4, client.player.blockPosition().getZ() >> 4);
    }

    private static String screen(Minecraft client) {
        return client.gui.screen() == null ? "none" : client.gui.screen().getClass().getName();
    }

    private boolean waitUntil(Predicate<Minecraft> condition, int ticks) {
        try {
            ctx().waitFor(condition, ticks);
            return true;
        } catch (AssertionError _) {
            return false;
        }
    }

    private void leave() {
        if (ctx().computeOnClient(client -> client.level != null)) {
            ctx().runOnClient(client -> client.disconnect(new TitleScreen(), false));
        } else if (!ctx().computeOnClient(client -> client.gui.screen() instanceof TitleScreen)) {
            ctx().setScreen(TitleScreen::new);
        }
        ctx().waitForScreen(TitleScreen.class);
    }

    private ClientGameTestContext ctx() {
        return Objects.requireNonNull(context, "runTest sets the context");
    }

    private static String env(String name) {
        return Objects.requireNonNullElse(System.getenv(name), "");
    }

    // ---- The report

    private void line(String step, boolean pass, String evidence) {
        lines.put(step, "rush " + step + (pass ? " PASS " : " FAIL ") + oneLine(evidence));
    }

    private void skip(List<String> steps, String why) {
        for (String step : steps) lines.put(step, "rush " + step + " SKIP " + oneLine(why));
    }

    private void write(String ended) {
        final List<String> out = new ArrayList<>();
        for (String step : STEPS) out.add(lines.getOrDefault(step, "rush " + step + " FAIL not reached: " + oneLine(ended)));
        final Path file = FabricLoader.getInstance().getGameDir().resolve("synaxis-gametest/rush.txt");
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not write " + file, e);
        }
    }

    private static String oneLine(String text) {
        return text.replace('\n', ' ').replace('\r', ' ');
    }
}
