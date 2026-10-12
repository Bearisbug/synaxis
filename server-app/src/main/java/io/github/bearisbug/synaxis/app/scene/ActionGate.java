package io.github.bearisbug.synaxis.app.scene;

/**
 * The limits on the actions of one connection. Skeleton: it limits nothing.
 */
final class ActionGate {

    boolean admit(long now) {
        return true;
    }

    long cooldownLeft(int slot, long now) {
        return 0;
    }

    void startCooldown(int slot, long now, long duration) {
    }

    void clearCooldowns() {
    }
}
