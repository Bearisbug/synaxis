package io.github.bearisbug.synaxis.plugin;

import io.github.bearisbug.synaxis.instance.Instance;

/**
 * The decision of an {@link AdmissionProvider} (API-M2-011).
 */
public sealed interface Admission {

    /**
     * The player is let in.
     *
     * @param instance the instance to spawn them into
     * @param team     the team they play for, {@code 0} being neutral
     */
    record Accept(Instance instance, int team) implements Admission {
    }

    /**
     * The player is turned away.
     *
     * @param reasonKey the disconnect reason key, one of the {@code synaxis:*} keys of
     *                  {@code docs/DESIGN-M2.md} §14
     */
    record Reject(String reasonKey) implements Admission {
    }
}
