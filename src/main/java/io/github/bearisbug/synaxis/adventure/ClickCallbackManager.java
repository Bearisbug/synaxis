package io.github.bearisbug.synaxis.adventure;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.IntArrayBinaryTag;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import io.github.bearisbug.synaxis.thread.Tickable;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientCustomClickActionPacket;
import io.github.bearisbug.synaxis.utils.UUIDUtils;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manager for Adventure click callbacks.
 */
public final class ClickCallbackManager implements Tickable {
    // Kept on the upstream namespace on purpose: this id travels to the client inside the
    // click_event of every callback component and comes back in ClientCustomClickActionPacket,
    // so changing it changes the wire bytes.
    private static final Key KEY = Key.key("minestom", "click_callback");

    private final Map<UUID, CallbackData> callbacks = new ConcurrentHashMap<>(0);

    private record CallbackData(ClickCallback<Audience> callback, long expiry, AtomicInteger uses) {
        boolean isExpired(final long time) {
            return !hasUsesLeft(this.uses.get()) || time >= this.expiry;
        }

        void consume(final Audience audience) {
            final int remaining = this.uses.getAndUpdate(current -> current > 0 ? current - 1 : current);
            if (hasUsesLeft(remaining) && this.expiry > System.nanoTime()) {
                this.callback.accept(audience);
            }
        }

        private static boolean hasUsesLeft(final int uses) {
            return uses > 0 || uses == ClickCallback.UNLIMITED_USES;
        }
    }

    /**
     * Drops the callbacks that expired or have no use left.
     *
     * @param time the {@link System#nanoTime()} of the tick, in nanoseconds, compared with the expiry of each callback.
     *             The server process calls this once per tick, not a tick thread
     */
    @Override
    public void tick(final long time) {
        if (this.callbacks.isEmpty()) return;
        this.callbacks.values().removeIf(data -> data.isExpired(time));
    }

    /**
     * Consumes a custom click event.
     *
     * @param player the player who performed the click
     * @param packet the packet
     */
    public void consumeCustomClick(final Player player, final ClientCustomClickActionPacket packet) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(packet, "packet");
        if (!packet.key().equals(KEY)) return;

        if (packet.payload() instanceof final IntArrayBinaryTag tag) {
            final UUID uuid;
            try {
                uuid = UUIDUtils.fromNbt(tag);
            } catch (final IndexOutOfBoundsException _) {
                return;
            }

            final CallbackData data = this.callbacks.get(uuid);
            if (data != null) {
                data.consume(player);
            }
        }
    }

    /**
     * Creates a click event from the given callback and options.
     *
     * @param callback the callback
     * @param options the options
     * @return the click event
     * @throws IllegalArgumentException if the options allow no click: the uses are neither positive nor
     *                                  {@link ClickCallback#UNLIMITED_USES}, or the lifetime is not positive
     */
    public ClickEvent<ClickEvent.Payload.Custom> createClickEvent(final ClickCallback<Audience> callback, final ClickCallback.Options options) {
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(options, "options");
        final UUID uuid = UUID.randomUUID();
        final int uses = options.uses();
        if (uses != ClickCallback.UNLIMITED_USES && uses <= 0)
            throw new IllegalArgumentException("uses must be > 0 or ClickCallback.UNLIMITED_USES (-1), got " + uses);
        if (!options.lifetime().isPositive())
            throw new IllegalArgumentException("lifetime must be positive, got " + options.lifetime());

        long expiry;
        try {
            expiry = Math.addExact(System.nanoTime(), options.lifetime().toNanos());
        } catch (final ArithmeticException _) {
            expiry = Long.MAX_VALUE;
        }
        this.callbacks.put(uuid, new CallbackData(callback, expiry, new AtomicInteger(uses)));

        return ClickEvent.custom(KEY, new BinaryTagHolderImpl(UUIDUtils.toNbt(uuid)));
    }
}
