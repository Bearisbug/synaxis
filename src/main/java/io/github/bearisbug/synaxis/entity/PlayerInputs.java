package io.github.bearisbug.synaxis.entity;

/**
 * The keys a player holds, as last sent by its client: a live view, updated in place on the player's tick thread
 * each time a {@code ClientInputPacket} comes in. Copy the values out to keep them.
 *
 * @see Player#inputs()
 */
public class PlayerInputs {

    private boolean forward;
    private boolean backward;
    private boolean left;
    private boolean right;
    private boolean jump;
    private boolean shift;
    private boolean sprint;

    public boolean forward() {
        return forward;
    }

    public boolean backward() {
        return backward;
    }

    public boolean left() {
        return left;
    }

    public boolean right() {
        return right;
    }

    public boolean jump() {
        return jump;
    }

    public boolean shift() {
        return shift;
    }

    public boolean sprint() {
        return sprint;
    }

    /**
     * Replaces the held keys. The engine calls it for every {@code ClientInputPacket}, through
     * {@link Player#refreshInput}, which also fires {@code PlayerInputEvent} and syncs sneaking; calling it directly
     * does neither.
     */
    public void refresh(boolean forward, boolean backward, boolean left, boolean right, boolean jump, boolean shift, boolean sprint) {
        this.forward = forward;
        this.backward = backward;
        this.left = left;
        this.right = right;
        this.jump = jump;
        this.shift = shift;
        this.sprint = sprint;
    }
}
