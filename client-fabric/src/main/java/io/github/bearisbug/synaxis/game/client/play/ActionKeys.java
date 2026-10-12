package io.github.bearisbug.synaxis.game.client.play;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * The action keys of the scene layer ({@code docs/DESIGN-PLAY.md} §6.4.8). S1 has one, {@value #ACTION_1}, by
 * default on H (the user's call of 2026-10-12: 26.2 keeps G for {@code key.quickActions}); W2 adds keys 2 to 6
 * on J, K, U, I and R. A press sends the action the current scene has on the key, through
 * {@link PlayClient#press}, which also keeps the key's local cooldown.
 * <p>
 * 26.2's {@link KeyMapping} takes a {@link KeyMapping.Category}; the category {@code synaxis:play} reads its name
 * from {@code key.category.synaxis.play} of the mod's language files.
 */
public final class ActionKeys {

    /** The name of action key 1, which is also its translation key. */
    public static final String ACTION_1 = "key.synaxis.action_1";

    /** The controls screen's category of the action keys. */
    public static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("synaxis", "play"));

    /** Action key 1, the scene's action slot 0. */
    public static final KeyMapping KEY_1 = new KeyMapping(ACTION_1, InputConstants.Type.KEYSYM, InputConstants.KEY_H, CATEGORY);

    private ActionKeys() {
    }

    /** Adds the keys to the controls; once, from the client entry point, before the options load. */
    public static void register() {
        KeyMappingHelper.registerKeyMapping(KEY_1);
    }

    /**
     * Turns this tick's presses into actions; on the client thread.
     *
     * @param play the connection's mirror
     * @return how many presses there were
     */
    public static int tick(PlayClient play) {
        return 0;
    }
}
