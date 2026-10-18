package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.entity.PlayerSkin;
import io.github.bearisbug.synaxis.event.trait.PlayerEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Called when the player first spawns, and after every return from the configuration phase, to set
 * the skin the others see.
 * <p>
 * Dispatched on the tick scheduler thread, in the serial part of the tick: a listener must not
 * block, every tick of the process waits for it. A skin fetched remotely
 * ({@link PlayerSkin#fromUsername}, {@link PlayerSkin#fromUuid}) is fetched beforehand, in
 * {@code AsyncPlayerConfigurationEvent} which runs on a virtual thread, kept by the plugin (a tag of
 * the player, for instance) and set here: a skin set on the player before this event is replaced by
 * the one this event ends with.
 */
public class PlayerSkinInitEvent implements PlayerEvent {

    private final Player player;
    private PlayerSkin skin;

    public PlayerSkinInitEvent(Player player, @Nullable PlayerSkin currentSkin) {
        this.player = player;
        this.skin = currentSkin;
    }

    /**
     * Gets the spawning skin of the player.
     *
     * @return the player skin, or null if not any
     */
    @Nullable
    public PlayerSkin getSkin() {
        return skin;
    }

    /**
     * Sets the spawning skin of the player.
     *
     * @param skin the new player skin
     */
    public void setSkin(@Nullable PlayerSkin skin) {
        this.skin = skin;
    }

    @Override
    public Player getPlayer() {
        return player;
    }
}
