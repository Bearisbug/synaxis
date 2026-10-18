package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.thread.Acquirable;
import org.jetbrains.annotations.ApiStatus;

public class ExperienceOrb extends Entity {

    private short experienceCount;

    public ExperienceOrb(short experienceCount) {
        super(EntityType.EXPERIENCE_ORB);
        this.experienceCount = experienceCount;
    }

    /**
     * Gets the experience count.
     * <p>
     * The count is only used by the server, as by {@link io.github.bearisbug.synaxis.event.item.PickupExperienceEvent}:
     * it is not sent to the clients, the size they show does not follow it.
     *
     * @return the experience count
     */
    public short getExperienceCount() {
        return experienceCount;
    }

    /**
     * Changes the experience count.
     * <p>
     * The count is only used by the server: the clients are not told, the size they show does not change.
     *
     * @param experienceCount the new experience count
     */
    public void setExperienceCount(short experienceCount) {
        this.experienceCount = experienceCount;
    }

    @ApiStatus.Experimental
    @SuppressWarnings("unchecked")
    @Override
    public Acquirable<? extends ExperienceOrb> acquirable() {
        return (Acquirable<? extends ExperienceOrb>) super.acquirable();
    }
}
