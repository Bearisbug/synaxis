package io.github.bearisbug.synaxis.game.client.play;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.bearisbug.synaxis.game.protocol.play.C2SAction;
import io.github.bearisbug.synaxis.game.protocol.play.PlayMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Action key 1 of {@code docs/DESIGN-PLAY.md} §6.4.8: its name, its default key H, its category
 * {@code synaxis:play} and their texts in both language files, and a press turned into the scene's action with
 * the local cooldown holding back the next.
 */
class ActionKeysTest {

    private static final long MS = 1_000_000L;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void actionKeyOneIsOnHInTheCategoryOfThePlayLayer() {
        final KeyMapping key = ActionKeys.KEY_1;

        assertEquals("key.synaxis.action_1", key.getName());
        assertEquals(InputConstants.Type.KEYSYM.getOrCreate(InputConstants.KEY_H), key.getDefaultKey());
        assertEquals("synaxis:play", key.getCategory().id().toString());
    }

    @Test
    void bothLanguageFilesNameTheKeyAndItsCategory() throws IOException {
        for (final String language : List.of("en_us", "zh_cn")) {
            final JsonObject lang = language(language);
            assertTrue(lang.has("key.synaxis.action_1"), language + " lacks key.synaxis.action_1");
            assertTrue(lang.has("key.category.synaxis.play"), language + " lacks key.category.synaxis.play");
        }
    }

    @Test
    void aPressSendsTheSceneActionAndASecondInsideTheCooldownIsHeldBack() {
        final List<PlayMessage> sent = new ArrayList<>();
        final long[] now = {5_000 * MS};
        final PlayClient play = new PlayClient(sent::add, _ -> {
        }, () -> now[0]);
        play.receive(PlayClientTest.scene(1, 2000), RegistryAccess.EMPTY);

        KeyMapping.click(ActionKeys.KEY_1.getDefaultKey());
        assertEquals(1, ActionKeys.tick(play));
        now[0] += 200 * MS;
        KeyMapping.click(ActionKeys.KEY_1.getDefaultKey());
        assertEquals(1, ActionKeys.tick(play));
        assertEquals(0, ActionKeys.tick(play));

        assertEquals(List.of(new C2SAction(1, 1, "probe:queue", new C2SAction.None())), sent);
        assertEquals(1, play.actionsSent());
    }

    private static JsonObject language(String language) throws IOException {
        final String path = "/assets/synaxis/lang/" + language + ".json";
        try (InputStream in = ActionKeysTest.class.getResourceAsStream(path)) {
            if (in == null) throw new AssertionError("no " + path + " on the test classpath");
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
