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

    private final Map<UUID, ClickCallback<Audience>> permanent = new ConcurrentHashMap<>(0);
    private final Map<UUID, CallbackData> temporary = new ConcurrentHashMap<>(0);

    private record CallbackData(ClickCallback<Audience> callback, long expiry, AtomicInteger uses) {
        private CallbackData {
            Objects.requireNonNull(callback, "callback");
            Objects.requireNonNull(uses, "uses");
        }

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

    @Override
    public void tick(final long time) {
        if (this.temporary.isEmpty()) return;
        this.temporary.values().removeIf(data -> data.isExpired(time));
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

            final ClickCallback<Audience> data = this.permanent.get(uuid);
            if (data != null) {
                data.accept(player);
            } else {
                final CallbackData temp = this.temporary.get(uuid);
                if (temp != null) {
                    temp.consume(player);
                }
            }
        }
    }

    /**
     * Creates a click event from the given callback and options.
     *
     * @param callback the callback
     * @param options the options
     * @return the click event
     */
    public ClickEvent<ClickEvent.Payload.Custom> createClickEvent(final ClickCallback<Audience> callback, final ClickCallback.Options options) {
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(options, "options");
        final UUID uuid = UUID.randomUUID();
        final int uses = options.uses();

        long expiry;
        try {
            expiry = System.nanoTime() + options.lifetime().toNanos();
        } catch (final ArithmeticException _) {
            expiry = Long.MAX_VALUE;
        }

        final boolean unlimited = uses == ClickCallback.UNLIMITED_USES;
        if (expiry == Long.MAX_VALUE && unlimited) {
            this.permanent.put(uuid, callback);
        } else if ((unlimited || uses > 0) && expiry > System.nanoTime()) {
            this.temporary.put(uuid, new CallbackData(callback, expiry, new AtomicInteger(uses)));
        }

        return ClickEvent.custom(KEY, new BinaryTagHolderImpl(UUIDUtils.toNbt(uuid)));
    }
}
