package io.github.bearisbug.synaxis.game.client.gametest.play;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;
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
 * until the client stands in the new level with no screen open and that chunk loaded. Q1 to Q9 are the steps of
 * §6.7.6 against {@value #RUSH_VARIABLE}.
 * <p>
 * Every step is one line {@code rush Q<n> PASS|FAIL|SKIP <evidence>} of {@code synaxis-gametest/rush.txt},
 * written in a {@code finally}; the test catches what its steps throw and never fails itself, and
 * {@code client-fabric/gametest-env/rush_judge.py} judges the file.
 */
public final class RushClientGameTest implements FabricClientGameTest {
    static final String RUSH_VARIABLE = "SYNAXIS_RUSH_SERVER";
    static final String FIXTURE_VARIABLE = "SYNAXIS_PLAY_FIXTURE_SERVER";

    /** The lines of the report, in the order they are written. */
    private static final List<String> STEPS = List.of("Q0a", "Q0b", "Q1", "Q2", "Q3", "Q4", "Q5", "Q6", "Q7", "Q8", "Q9");

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

    private static final int CONNECT_TIMEOUT_TICKS = 20 * 120;
    private static final int HUD_TIMEOUT_TICKS = 20 * 20;
    private static final int FRAME_TICKS = 10;
    private static final int MOVE_TIMEOUT_TICKS = 20 * 30;
    private static final int ANSWER_TIMEOUT_TICKS = 20 * 5;
    /** A colour shows on the screenshot when at least this many pixels are within {@value #NEAR} of it. */
    private static final int MIN_PIXELS = 20;
    private static final int NEAR = 24;

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
                skip(List.of("Q0a", "Q0b"), FIXTURE_VARIABLE + " not set");
            } else {
                q0(fixture);
                leave();
            }
            if (rush.isBlank()) skip(STEPS.subList(2, STEPS.size()), RUSH_VARIABLE + " not set");
            else for (String step : STEPS.subList(2, STEPS.size())) line(step, false, "not in this build of the script");
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
        ctx().runOnClient(client -> ConnectScreen.startConnecting(new TitleScreen(), client, ServerAddress.parseString(server),
                new ServerData("play fixture", server, ServerData.Type.OTHER), false, null));
        final boolean joined = waitUntil(client -> inWorld(client) || client.gui.screen() instanceof DisconnectedScreen,
                CONNECT_TIMEOUT_TICKS) && ctx().computeOnClient(RushClientGameTest::inWorld);
        if (!joined) {
            final String why = "not in the world of " + server + ", screen " + ctx().computeOnClient(RushClientGameTest::screen);
            line("Q0a", false, why);
            line("Q0b", false, why);
            return;
        }
        q0a();
        q0b();
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
