package io.github.bearisbug.synaxis.network.packet.server;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.event.HoverEvent;
import io.github.bearisbug.synaxis.JvmFlags;
import io.github.bearisbug.synaxis.adventure.ComponentHolder;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.PacketWriting;
import io.github.bearisbug.synaxis.registry.Registries;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.ref.SoftReference;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Represents a packet whose framed representation is computed lazily and
 * cached for reuse.
 *
 * <p>The cached value is stored in a {@link SoftReference}, allowing it to be
 * reclaimed in response to memory pressure. The value is recomputed when the
 * reference has been cleared or when {@link #invalidate()} is called.
 *
 * <p>When caching is enabled, filling the cache is serialized: {@link #body}
 * computes a replacement value on one thread at a time. Cache reads and
 * publication use acquire and release memory semantics.
 *
 * <p>The supplier may be invoked concurrently all the same and must be thread
 * safe: {@link #packet()} invokes it directly whenever no value is cached,
 * whether caching is enabled or not, outside of that serialization.
 *
 * <p>The supplied packet and its serialized representation must remain valid
 * for the {@link ConnectionState} used to populate the cache. A
 * {@code CachedPacket} should not be shared across connection states when the
 * resulting framed representation differs between those states.
 *
 * <p>Sent to a connection, the supplier runs on the write thread of that
 * connection. When it throws, that connection drops the packet and is kicked
 * with {@code synaxis:internal-error}; nothing is cached, so the next
 * connection the packet is sent to runs the supplier again.
 */
@ApiStatus.Internal
public final class CachedPacket implements SendablePacket {
    private static final VarHandle PACKET;
    private static final VarHandle INVALIDATIONS;

    static {
        try {
            final MethodHandles.Lookup lookup = MethodHandles.lookup();
            PACKET = lookup.findVarHandle(CachedPacket.class, "packet", SoftReference.class);
            INVALIDATIONS = lookup.findVarHandle(CachedPacket.class, "invalidations", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Wraps a packet meant for several players into a cached packet, unless {@code synaxis.grouped-packet} or
     * {@code synaxis.cached-packet} is off, or the packet holds components that have to be translated per player.
     * <p>
     * Note: {@link ServerPacket.ComponentHolding}s are not translated inside a {@code CachedPacket}.
     *
     * @see #body(ConnectionState, Registries, int)
     */
    public static SendablePacket grouped(ServerPacket packet) {
        return JvmFlags.GROUPED_PACKET && JvmFlags.CACHED_PACKET && shouldCache(packet) ? new CachedPacket(packet) : packet;
    }

    private static boolean shouldCache(ServerPacket packet) {
        if (!JvmFlags.AUTOMATIC_COMPONENT_TRANSLATION) return true;
        if (!(packet instanceof ServerPacket.ComponentHolding holder)) return true;
        return !containsTranslatableComponents(holder);
    }

    private static boolean containsTranslatableComponents(ComponentHolder<?> holder) {
        for (final Component component : holder.components()) {
            if (isTranslatable(component)) return true;
        }
        return false;
    }

    private static boolean isTranslatable(Component component) {
        if (component instanceof TranslatableComponent) return true;
        final HoverEvent<?> hoverEvent = component.hoverEvent();
        if (hoverEvent != null && hoverEvent.value() instanceof Component hoverComponent && isTranslatable(hoverComponent))
            return true;
        final List<Component> children = component.children();
        if (children.isEmpty()) return false;
        for (final Component child : children) {
            if (isTranslatable(child)) return true;
        }
        return false;
    }

    private final Supplier<ServerPacket> packetSupplier;

    // Accessed through PACKET using acquire/release semantics.
    @SuppressWarnings("unused")
    private @Nullable SoftReference<FramedPacket> packet;

    // Accessed through INVALIDATIONS, counts the invalidations to detect the ones racing a computation.
    @SuppressWarnings("unused")
    private volatile long invalidations;

    /**
     * Creates a cached packet backed by the given packet supplier.
     *
     * <p>The supplier is invoked whenever the framed packet must be recomputed.
     * It may therefore be invoked multiple times over the lifetime of this
     * object.
     *
     * <p>The supplier may be invoked concurrently and must be thread safe: only
     * filling the cache through {@link #body} is serialized, {@link #packet()}
     * invokes it directly whenever no value is cached.
     *
     * @param packetSupplier the packet supplier
     * @throws NullPointerException if {@code packetSupplier} is {@code null}
     */
    public CachedPacket(Supplier<ServerPacket> packetSupplier) {
        this.packetSupplier = Objects.requireNonNull(packetSupplier, "packetSupplier");
    }

    /**
     * Creates a cached packet backed by a constant packet value.
     *
     * @param packet the packet to frame and cache
     * @throws NullPointerException if {@code packet} is {@code null}
     */
    public CachedPacket(ServerPacket packet) {
        Objects.requireNonNull(packet, "packet");
        this(() -> packet);
    }

    /**
     * Invalidates the currently cached packet, if any.
     *
     * <p>The next cache access recomputes the packet unless caching is disabled.
     * This method has no effect when caching is disabled.
     *
     * @implSpec This method clears the cached reference using release semantics.
     * Cache computation is not synchronized with invalidation, but a computation
     * that started before this method was called discards its result instead of
     * publishing it.
     */
    public void invalidate() {
        if (!JvmFlags.CACHED_PACKET) return;
        INVALIDATIONS.getAndAdd(this, 1L);
        PACKET.setRelease(this, null);
    }

    /**
     * Returns the packet represented by this cached value.
     *
     * <p>The cached value is reused when one is present, otherwise the supplier is
     * invoked directly without populating the cache, since framing needs the registries
     * and compression threshold of the sending process. The supplier may therefore be
     * invoked concurrently by multiple callers.
     *
     * @return the packet value
     */
    public ServerPacket packet() {
        final FramedPacket cache = cachedPacket();
        return cache != null ? cache.packet() : packetSupplier.get();
    }

    /**
     * Returns the cached framed packet body for the given connection state.
     *
     * <p>If caching is enabled, this method initializes the cache when necessary.
     * Cache computation and supplier invocation are serialized.
     *
     * <p>If caching is disabled, no framed body is created and {@code null} is
     * returned.
     *
     * @param state                the connection state used when framing the packet
     * @param registries           the registries of the owning process
     * @param compressionThreshold the compression threshold of the owning process
     * @return the framed packet body, or {@code null} when caching is disabled
     */
    public @Nullable NetworkBuffer body(ConnectionState state, @Nullable Registries registries, int compressionThreshold) {
        if (!JvmFlags.CACHED_PACKET) return null;
        final FramedPacket cache = cachedPacket();
        return (cache != null ? cache : computeCache(state, registries, compressionThreshold)).body();
    }

    /**
     * Returns an existing cached value or computes and publishes a replacement.
     *
     * <p>This method is synchronized so that only one thread invokes the packet
     * supplier and computes a replacement cache entry at a time. The cache is
     * checked again after acquiring this object's monitor because another thread
     * may have populated it while the current thread was waiting.
     *
     * <p>Invalidation does not acquire this monitor. The invalidation count is
     * read before the supplier is invoked and checked again before publication,
     * so an invalidation occurring during the computation drops the result
     * instead of being overwritten by it. The caller still gets that result,
     * which is no older than its own request.
     *
     * @param state the connection state used when framing the packet
     * @return the existing or newly computed framed packet
     */
    private synchronized FramedPacket computeCache(ConnectionState state, @Nullable Registries registries, int compressionThreshold) {
        final FramedPacket cache = cachedPacket();
        if (cache != null) return cache;

        final long invalidations = (long) INVALIDATIONS.getAcquire(this);
        final ServerPacket packet = packetSupplier.get();
        final NetworkBuffer buffer = PacketWriting.allocateTrimmedPacket(state, packet, registries, compressionThreshold);

        final FramedPacket updated = new FramedPacket(packet, buffer);
        if ((long) INVALIDATIONS.getAcquire(this) == invalidations) {
            PACKET.setRelease(this, new SoftReference<>(updated));
        }
        return updated;
    }

    /**
     * Returns the currently referenced cached packet.
     *
     * <p>The cached reference is read using acquire semantics.
     *
     * @return the cached framed packet, or {@code null} if no cache exists or
     * the soft reference has been cleared
     */
    @SuppressWarnings("unchecked")
    private @Nullable FramedPacket cachedPacket() {
        final SoftReference<FramedPacket> ref = (SoftReference<FramedPacket>) PACKET.getAcquire(this);
        return ref != null ? ref.get() : null;
    }

    /**
     * Returns whether a framed packet is currently cached and has not been
     * reclaimed.
     *
     * <p>This result is inherently transient because the underlying soft
     * reference may be cleared or the cache may be invalidated immediately
     * after this method returns.
     *
     * @return {@code true} if caching is enabled and a cached value is currently
     * available
     */
    public boolean isValid() {
        return JvmFlags.CACHED_PACKET && cachedPacket() != null;
    }

    /**
     * Returns a string representation containing the currently referenced
     * cached value.
     *
     * <p>Calling this method does not invoke the packet supplier or repopulate
     * the cache.
     *
     * @return a string representation of this cached packet
     */
    @Override
    public String toString() {
        return String.format("CachedPacket{cache=%s}", cachedPacket());
    }
}