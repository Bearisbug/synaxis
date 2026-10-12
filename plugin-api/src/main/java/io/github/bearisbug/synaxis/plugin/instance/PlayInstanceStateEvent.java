package io.github.bearisbug.synaxis.plugin.instance;

import io.github.bearisbug.synaxis.event.trait.InstanceEvent;
import io.github.bearisbug.synaxis.instance.Instance;

/**
 * A managed instance changed state ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-006}). The host calls
 * it after every transition, on the thread that carried the transition out. It is an instance event,
 * so a listener scoped to the instance with {@code PluginEvents#forInstance} receives it as well as
 * a global one; every plugin sees the transitions of every instance.
 *
 * @param managed the instance
 * @param from    the state before
 * @param to      the state after
 */
public record PlayInstanceStateEvent(ManagedInstance managed, ManagedInstance.State from, ManagedInstance.State to)
        implements InstanceEvent {

    @Override
    public Instance getInstance() {
        return managed.instance();
    }
}
