package dev.gameengine.utils;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.L_Result;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;

@JCStressTest
@Outcome(id = "1", expect = ACCEPTABLE)
@Outcome(id = "2", expect = ACCEPTABLE)
@State
public class ObjectPoolTest {
    // A plain supplier keeps the state self-contained, the shared PacketVanilla.PACKET_POOL
    // would tie this test to the packet layer.
    private final ObjectPool<Object> pool = ObjectPool.pool(Object::new);

    @Actor
    public void actor1() {
        var pooled = pool.get();
        pool.add(pooled);
    }

    @Actor
    public void actor2() {
        var pooled = pool.get();
        pool.add(pooled);
    }

    @Arbiter
    public void arbiter(L_Result r) {
        r.r1 = pool.count();
    }
}
