package dev.gameengine.event.item;

import dev.gameengine.entity.ExperienceOrb;
import dev.gameengine.entity.Player;
import dev.gameengine.event.trait.CancellableEvent;
import dev.gameengine.event.trait.PlayerInstanceEvent;

public class PickupExperienceEvent implements CancellableEvent, PlayerInstanceEvent {

    private final Player player;
    private final ExperienceOrb experienceOrb;
    private short experienceCount;

    private boolean cancelled;

    public PickupExperienceEvent(Player player, ExperienceOrb experienceOrb) {
        this.player = player;
        this.experienceOrb = experienceOrb;
        this.experienceCount = experienceOrb.getExperienceCount();
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    public ExperienceOrb getExperienceOrb() {
        return experienceOrb;
    }

    public short getExperienceCount() {
        return experienceCount;
    }

    public void setExperienceCount(short experienceCount) {
        this.experienceCount = experienceCount;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
}
