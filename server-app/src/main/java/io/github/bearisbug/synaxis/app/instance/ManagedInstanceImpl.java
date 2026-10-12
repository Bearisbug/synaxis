package io.github.bearisbug.synaxis.app.instance;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.LightingChunk;
import io.github.bearisbug.synaxis.instance.generator.Generator;
import io.github.bearisbug.synaxis.plugin.instance.EndCause;
import io.github.bearisbug.synaxis.plugin.instance.Exit;
import io.github.bearisbug.synaxis.plugin.instance.InstanceSpec;
import io.github.bearisbug.synaxis.plugin.instance.ManagedInstance;
import io.github.bearisbug.synaxis.plugin.instance.PlayInstanceStateEvent;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.timer.TaskSchedule;
import io.github.bearisbug.synaxis.world.DimensionType;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * One managed instance and its state machine ({@code docs/DESIGN-PLAY.md} §11.1, {@code API-PLAY-006}).
 * <p>
 * The state and the end cause are swapped together, and a transition is done once the swap is: a
 * closing instance is {@link State#CLOSED} from then on while its players are sent away and it is
 * unregistered. The state event is called after the swap, on the thread that made it.
 */
final class ManagedInstanceImpl implements ManagedInstance {

    private static final Logger LOGGER = LoggerFactory.getLogger(ManagedInstanceImpl.class);

    /** The causes a plugin may end an instance with; the others belong to the host. */
    private static final Set<EndCause> PLUGIN_ENDS =
            EnumSet.of(EndCause.OBJECTIVE, EndCause.TIME_LIMIT, EndCause.EMPTY, EndCause.ERROR);

    /** The states a forced close leaves, in the order it tries them. */
    private static final List<State> CLOSABLE = List.of(State.PREPARING, State.OPEN, State.RUNNING, State.ENDING);

    /**
     * The state and the end cause as one value, so that no reader sees a cause without its state.
     *
     * @param state the state
     * @param cause the end cause, null before {@link State#ENDING} or {@link State#CLOSED}
     */
    private record Status(State state, @Nullable EndCause cause) {
    }

    private final InstanceRegistry registry;
    private final PluginInstancesImpl owner;
    private final InstanceSpec spec;
    private final String kindId;
    private final String id;
    private final InstanceContainer instance;
    private final long createdAt;
    private final AtomicReference<Status> status = new AtomicReference<>(new Status(State.PREPARING, null));
    /**
     * The first exception the plugin's generator threw. The engine hands a generator's exception to the
     * exception handler of the process and counts the chunk as loaded, so without this the host would
     * open an instance whose map is missing.
     */
    private final AtomicReference<Throwable> generatorFailure = new AtomicReference<>();
    /** Completes once every chunk is loaded and lit, exceptionally when that failed. */
    private final CompletableFuture<Void> preparation = new CompletableFuture<>();
    private volatile long endingAt;
    private volatile long registerNanos = -1;
    private volatile @Nullable Evacuation evacuation;

    /**
     * Builds the unregistered engine instance; nothing is loaded until one of the two prepare methods.
     *
     * @param serial the number of this instance among those of its plugin with the same name, from 1
     */
    ManagedInstanceImpl(InstanceRegistry registry, PluginInstancesImpl owner, InstanceSpec spec,
                        RegistryKey<DimensionType> dimensionType, int serial) {
        this.registry = registry;
        this.owner = owner;
        this.spec = spec;
        this.kindId = owner.pluginId() + ":" + spec.name();
        this.id = kindId + "_" + serial;
        this.instance = new InstanceContainer(registry.process(), UUID.randomUUID(), dimensionType, Key.key(id));
        instance.setChunkSupplier(LightingChunk::new);
        final Generator generator = spec.generator();
        instance.setGenerator(unit -> {
            try {
                generator.generate(unit);
            } catch (Throwable throwable) {
                generatorFailure.compareAndSet(null, throwable);
            }
        });
        this.createdAt = registry.now();
    }

    /**
     * Prepares the instance on the calling thread, the startup thread: waits for every chunk, at most
     * the prepare budget, lights them and registers the instance.
     */
    void prepareInPlace() {
        registry.ledger().created(this, spec.chunkRadius());
        try {
            loadAll().get(registry.durations().prepareBudget().toNanos(), TimeUnit.NANOSECONDS);
            LightingChunk.relight(instance, instance.getChunks());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failInPlace(e);
            return;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            failInPlace(e instanceof ExecutionException ? Objects.requireNonNullElse(e.getCause(), e) : e);
            return;
        }
        preparation.complete(null);
        open();
    }

    /**
     * Starts the preparation outside the tick: the chunks generate on the engine's virtual threads and
     * are lit on a virtual thread of their own, and every serial part of the tick scheduler thread
     * looks once whether that is done.
     */
    void prepareOutsideTheTick() {
        registry.ledger().created(this, spec.chunkRadius());
        var _ = loadAll()
                .thenRunAsync(() -> LightingChunk.relight(instance, instance.getChunks()), Thread::startVirtualThread)
                .whenComplete((_, failure) -> {
                    if (failure == null) preparation.complete(null);
                    else preparation.completeExceptionally(failure);
                });
        registry.process().scheduler().scheduleTask(this::awaitPreparation, TaskSchedule.nextTick());
    }

    private CompletableFuture<Void> loadAll() {
        final int radius = spec.chunkRadius();
        final List<CompletableFuture<Chunk>> chunks = new ArrayList<>((2 * radius + 1) * (2 * radius + 1));
        for (int chunkX = -radius; chunkX <= radius; chunkX++) {
            for (int chunkZ = -radius; chunkZ <= radius; chunkZ++) {
                chunks.add(instance.loadChunk(chunkX, chunkZ));
            }
        }
        return CompletableFuture.allOf(chunks.toArray(CompletableFuture[]::new)).thenRun(() -> {
            final Throwable failure = generatorFailure.get();
            if (failure != null) throw new CompletionException("the generator of " + id + " threw", failure);
        });
    }

    private void failInPlace(Throwable failure) {
        preparation.completeExceptionally(failure);
        failPreparation(failure);
    }

    /** Once a tick, in the serial part of the tick scheduler thread, while the instance prepares outside the tick. */
    private TaskSchedule awaitPreparation() {
        // Dropped by a forced close: nothing is registered any more, whenever the preparation ends
        if (state() != State.PREPARING) return TaskSchedule.stop();
        if (preparation.isDone()) {
            if (preparation.state() == Future.State.FAILED) failPreparation(preparation.exceptionNow());
            else open();
            return TaskSchedule.stop();
        }
        if (registry.now() - createdAt >= registry.durations().prepareBudget().toNanos()) {
            failPreparation(new TimeoutException("not prepared within " + registry.durations().prepareBudget()));
            return TaskSchedule.stop();
        }
        return TaskSchedule.nextTick();
    }

    /**
     * {@code PREPARING → OPEN}. Registered before the swap: a forced close on another thread that
     * swaps first leaves the swap here failing, and the instance is unregistered again, so no order
     * leaves a closed instance registered.
     */
    private void open() {
        final long start = System.nanoTime();
        instance.enableAutoChunkLoad(false);
        registry.process().instance().registerInstance(instance);
        if (!swap(State.PREPARING, State.OPEN, null)) {
            registry.process().instance().unregisterInstance(instance);
            return;
        }
        registry.ledger().opened(this, millisSinceCreated());
        dispatch(State.PREPARING, State.OPEN);
        this.registerNanos = System.nanoTime() - start;
    }

    private void failPreparation(Throwable failure) {
        if (!swap(State.PREPARING, State.CLOSED, EndCause.PREPARE_FAILED)) return;
        LOGGER.error("Managed instance failed to prepare: plugin_id={} instance={}", owner.pluginId(), id, failure);
        closed(0, 0, 0, false);
        dispatch(State.PREPARING, State.CLOSED);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public UUID instanceId() {
        return instance.getUuid();
    }

    @Override
    public InstanceContainer instance() {
        return instance;
    }

    @Override
    public State state() {
        return status.get().state();
    }

    @Override
    public @Nullable EndCause endCause() {
        return status.get().cause();
    }

    @Override
    public Set<Player> players() {
        return Set.copyOf(instance.getPlayers());
    }

    @Override
    public CompletableFuture<Boolean> move(Player player, Pos at) {
        if (!takesPlayers()) return CompletableFuture.completedFuture(false);
        final CompletableFuture<Boolean> moved = new CompletableFuture<>();
        registry.scenes().holdUntil(player, moved);
        // Whichever of the player's next tick and the check for a player gone comes first decides
        final AtomicBoolean decided = new AtomicBoolean();
        player.scheduler().scheduleNextTick(() -> {
            if (decided.compareAndSet(false, true)) moveNow(player, at, moved);
        });
        // A player that leaves before its next tick never runs the task above
        registry.process().scheduler().scheduleTask(() -> {
            if (decided.get()) return TaskSchedule.stop();
            if (player.isOnline() && !player.isRemoved()) return TaskSchedule.nextTick();
            if (decided.compareAndSet(false, true)) moved.complete(false);
            return TaskSchedule.stop();
        }, TaskSchedule.nextTick());
        return moved;
    }

    /** The move itself, on the tick thread that owns the player. */
    private void moveNow(Player player, Pos at, CompletableFuture<Boolean> moved) {
        if (!takesPlayers() || !player.isOnline()) {
            moved.complete(false);
            return;
        }
        final Instance from = player.getInstance();
        if (from == instance) {
            moved.complete(true);
            return;
        }
        var _ = player.setInstance(instance, at).whenComplete((_, failure) -> {
            if (failure != null) {
                moved.completeExceptionally(failure);
                return;
            }
            registry.ledger().moved(this, player, from);
            moved.complete(true);
        });
    }

    @Override
    public void start() {
        request(State.OPEN, State.RUNNING, null, "start");
        registry.ledger().started(this, onlinePlayers());
        dispatch(State.OPEN, State.RUNNING);
    }

    @Override
    public void cancel(Function<Player, Exit> exit) {
        request(State.OPEN, State.CLOSED, EndCause.CANCELLED, "cancel");
        evacuate(State.OPEN, exit, false);
    }

    @Override
    public void end(EndCause cause) {
        if (!PLUGIN_ENDS.contains(cause))
            throw new IllegalArgumentException("a plugin ends an instance with " + PLUGIN_ENDS + ", not " + cause);
        request(State.RUNNING, State.ENDING, cause, "end");
        this.endingAt = registry.now();
        registry.ledger().ended(this, cause, onlinePlayers(), millisSinceCreated());
        registry.process().scheduler().scheduleTask(this::awaitSettlement, TaskSchedule.nextTick());
        dispatch(State.RUNNING, State.ENDING);
    }

    /** Once a tick on the tick scheduler thread while the instance is ending: the settle timeout. */
    private TaskSchedule awaitSettlement() {
        if (state() != State.ENDING) return TaskSchedule.stop();
        if (registry.now() - endingAt < spec.settleTimeout().toNanos()) return TaskSchedule.nextTick();
        if (swap(State.ENDING, State.CLOSED, null)) {
            LOGGER.warn("Managed instance was not settled within its settle timeout, closing it: plugin_id={} "
                    + "instance={} settle_timeout_ms={}", owner.pluginId(), id, spec.settleTimeout().toMillis());
            evacuate(State.ENDING, spec.fallbackExit(), true);
        }
        return TaskSchedule.stop();
    }

    @Override
    public void settled(Function<Player, Exit> exit) {
        request(State.ENDING, State.CLOSED, null, "settled");
        evacuate(State.ENDING, exit, false);
    }

    private void evacuate(State from, Function<Player, Exit> exit, boolean settleTimeout) {
        final Evacuation started = new Evacuation(this, registry, settleTimeout);
        this.evacuation = started;
        started.start(exit);
        dispatch(from, State.CLOSED);
    }

    /**
     * The forced close of §11.1: an instance still preparing is dropped, an open, running or ending one
     * is unregistered on the calling thread without any exit, and one already being evacuated is
     * finished at once.
     *
     * @param cause {@link EndCause#PLUGIN_DISABLED} or {@link EndCause#SHUTDOWN}
     */
    void reclaim(EndCause cause) {
        for (State from : CLOSABLE) {
            if (!swap(from, State.CLOSED, cause)) continue;
            if (from == State.PREPARING) {
                closed(0, 0, 0, false);
            } else {
                final Evacuation forced = new Evacuation(this, registry, false);
                this.evacuation = forced;
                forced.finish();
            }
            dispatch(from, State.CLOSED);
            return;
        }
        final Evacuation running = this.evacuation;
        if (running != null) running.finish();
    }

    /**
     * Writes the closing line once the instance is unregistered, or was dropped before it ever was,
     * and forgets it.
     */
    void closed(int players, int evacuated, int kicked, boolean settleTimeout) {
        registry.ledger().closed(this, Objects.requireNonNull(endCause()), players, millisSinceCreated(),
                evacuated, kicked, settleTimeout);
        owner.closed(this);
    }

    String pluginId() {
        return owner.pluginId();
    }

    String kindId() {
        return kindId;
    }

    /**
     * The preparation, for a test to wait on.
     *
     * @return completes once every chunk is loaded and lit, exceptionally when that failed
     */
    CompletableFuture<Void> preparation() {
        return preparation;
    }

    /**
     * What the registration took on its thread, the second of the two figures of §15.
     *
     * @return nanoseconds from turning automatic loading off to the state event returning, -1 before
     */
    long registerNanos() {
        return registerNanos;
    }

    private boolean takesPlayers() {
        final State state = state();
        return state == State.OPEN || state == State.RUNNING;
    }

    private int onlinePlayers() {
        return (int) instance.getPlayers().stream().filter(Player::isOnline).count();
    }

    private long millisSinceCreated() {
        return (registry.now() - createdAt) / 1_000_000L;
    }

    private void request(State from, State to, @Nullable EndCause cause, String request) {
        if (!swap(from, to, cause))
            throw new IllegalStateException(request + " needs a managed instance in " + from + ", " + id + " is " + state());
    }

    /**
     * Swaps the state from {@code from} to {@code to}, keeping the cause unless one is given.
     *
     * @return false if the state was not {@code from}
     */
    private boolean swap(State from, State to, @Nullable EndCause cause) {
        Status current;
        do {
            current = status.get();
            if (current.state() != from) return false;
        } while (!status.compareAndSet(current, new Status(to, cause == null ? current.cause() : cause)));
        return true;
    }

    private void dispatch(State from, State to) {
        registry.process().eventHandler().call(new PlayInstanceStateEvent(this, from, to));
    }
}
