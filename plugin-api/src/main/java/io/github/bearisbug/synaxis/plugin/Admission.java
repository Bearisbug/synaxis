package io.github.bearisbug.synaxis.plugin;

import io.github.bearisbug.synaxis.instance.Instance;

import java.util.Objects;

/**
 * The decision of an {@link AdmissionProvider} (API-M2-011).
 */
public sealed interface Admission {

    /**
     * The player is let in.
     *
     * @param instance the instance to spawn them into
     * @param team     the team they play for, {@code 0} being neutral; the host neither keeps nor
     *                 reads it, so a plugin that needs the team records it itself by
     *                 {@link AdmissionRequest#connectionId()} and drops it on
     *                 {@code PlayerDisconnectEvent}
     */
    record Accept(Instance instance, int team) implements Admission {

        /**
         * Checks that there is an instance to spawn into.
         *
         * @throws NullPointerException if the instance is null
         */
        public Accept {
            Objects.requireNonNull(instance, "instance");
        }
    }

    /**
     * The player is turned away.
     *
     * @param reasonKey the disconnect reason key, one the client mod has a sentence for: a constant
     *                  of {@link DisconnectKeys}, or a key this plugin names in
     *                  {@link SynaxisPlugin#translationKeys()}
     */
    record Reject(String reasonKey) implements Admission {

        /**
         * Checks that there is a reason key to disconnect with.
         *
         * @throws NullPointerException if the reason key is null
         */
        public Reject {
            Objects.requireNonNull(reasonKey, "reasonKey");
        }
    }
}
