package io.github.bearisbug.synaxis.event.item;

import io.github.bearisbug.synaxis.entity.ExperienceOrb;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.CancellableEvent;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;

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
