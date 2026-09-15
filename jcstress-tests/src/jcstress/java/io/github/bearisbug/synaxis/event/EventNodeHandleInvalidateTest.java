package io.github.bearisbug.synaxis.event;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.Z_Result;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

/**
 * Rebuilding a handle concurrently with an expired listener being dropped must never leave the
 * handle believing it is up to date while holding no listener at all.
 */
@JCStressTest
@Outcome(id = "true", expect = ACCEPTABLE)
@Outcome(id = "false", expect = FORBIDDEN, desc = "handle went permanently deaf")
@State
public class EventNodeHandleInvalidateTest {

    record Ev() implements Event {
    }

    private final EventNode<Event> node = EventNode.all("main");

    {
        node.addListener(Ev.class, _ -> {
        });
    }

    @Actor
    public void actor1() {
        // Forces the handle to be rebuilt
        node.addListener(EventListener.builder(Ev.class).handler(_ -> {
        }).build());
        node.getHandle(Ev.class).hasListener();
    }

    @Actor
    public void actor2() {
        // Expiring listener drops itself from the node while the handle is being rebuilt
        node.addListener(EventListener.builder(Ev.class).expireCount(1).handler(_ -> {
        }).build());
        node.call(new Ev());
    }

    @Arbiter
    public void arbiter(Z_Result r) {
        r.r1 = node.getHandle(Ev.class).hasListener();
    }
}
