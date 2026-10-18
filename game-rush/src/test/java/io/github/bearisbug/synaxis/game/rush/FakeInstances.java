package io.github.bearisbug.synaxis.game.rush;

import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.plugin.instance.InstanceSpec;
import io.github.bearisbug.synaxis.plugin.instance.ManagedInstance;
import io.github.bearisbug.synaxis.plugin.instance.PluginInstances;
import io.github.bearisbug.synaxis.testing.Env;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The host's managed instances as rush sees them ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-006}): a
 * create builds a real instance of the test's {@code Env} with the generator of the spec and every chunk
 * in its radius loaded, named the way the host names it, and hands it back in the state the test chose.
 */
final class FakeInstances implements PluginInstances {

    final List<InstanceSpec> specs = new CopyOnWriteArrayList<>();
    final List<FakeManaged> created = new CopyOnWriteArrayList<>();
    private final Env env;
    /** The state a create hands back: {@code OPEN} as on the startup thread, {@code PREPARING} as in a tick. */
    volatile ManagedInstance.State handBack;

    FakeInstances(Env env, ManagedInstance.State handBack) {
        this.env = env;
        this.handBack = handBack;
    }

    @Override
    public ManagedInstance create(InstanceSpec spec) {
        specs.add(spec);
        final InstanceContainer instance = env.process().instance().createInstanceContainer();
        instance.setGenerator(spec.generator());
        final int radius = spec.chunkRadius();
        final List<CompletableFuture<?>> chunks = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) chunks.add(instance.loadChunk(x, z));
        }
        chunks.forEach(CompletableFuture::join);
        final FakeManaged managed = new FakeManaged("rush:" + spec.name() + "_" + (created.size() + 1), instance,
                spec.settleTimeout(), handBack);
        created.add(managed);
        return managed;
    }

    @Override
    public List<ManagedInstance> owned() {
        return created.stream().filter(managed -> managed.state() != ManagedInstance.State.CLOSED)
                .map(ManagedInstance.class::cast).toList();
    }

    /**
     * The last instance created.
     *
     * @return it
     */
    FakeManaged last() {
        return created.getLast();
    }
}
