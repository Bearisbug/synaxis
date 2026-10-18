package io.github.bearisbug.synaxis.event;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.ZZ_Result;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

/**
 * Two threads adding the first listeners of a mapped node at once: each of them, once its call
 * returns, must find its listener in the dispatch of the holder, whichever of the two registered
 * the node.
 */
@JCStressTest
@Outcome(id = "true, true", expect = ACCEPTABLE)
@Outcome(id = {"false, true", "true, false", "false, false"}, expect = FORBIDDEN,
        desc = "a listener added to a mapped node that was not registered yet")
@State
public class EventNodeLazyEnsureMapTest {

    record Ev(Object owner) implements Event {
    }

    private static final EventFilter<Ev, Object> OWNER = EventFilter.from(Ev.class, Object.class, Ev::owner);

    private final Object owner = new Object();
    private final EventNode<Event> holder = EventNode.all("holder");
    // The holder only references the mapped node weakly
    private final EventNode<Ev> mapped = holder.map(owner, OWNER);

    @Actor
    public void actor1(ZZ_Result r) {
        mapped.addListener(Ev.class, _ -> {
        });
        r.r1 = holder.hasListener(Ev.class);
    }

    @Actor
    public void actor2(ZZ_Result r) {
        mapped.addListener(Ev.class, _ -> {
        });
        r.r2 = holder.hasListener(Ev.class);
    }
}
