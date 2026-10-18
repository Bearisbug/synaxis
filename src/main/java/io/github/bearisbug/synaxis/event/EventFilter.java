package io.github.bearisbug.synaxis.event;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.BlockEvent;
import io.github.bearisbug.synaxis.event.trait.EntityEvent;
import io.github.bearisbug.synaxis.event.trait.EntityInstanceEvent;
import io.github.bearisbug.synaxis.event.trait.InstanceEvent;
import io.github.bearisbug.synaxis.event.trait.InventoryEvent;
import io.github.bearisbug.synaxis.event.trait.ItemEvent;
import io.github.bearisbug.synaxis.event.trait.PlayerEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.inventory.AbstractInventory;
import io.github.bearisbug.synaxis.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * Represents a filter for a specific {@link Event} type.
 * <p>
 * The handler represents a "target" of the event. This can be used
 * to create filters for all events of a specific type using information
 * about the target.
 * <p>
 * For example, the target of a {@link PlayerEvent} is a {@link Player} so
 * you could create a player event filter which checks if the target player
 * is in creative mode.
 *
 * @param <E> The event type to filter
 * @param <H> The handler type to filter on.
 */
public interface EventFilter<E extends Event, H> {

    EventFilter<Event, ?> ALL = from(Event.class, null, null);
    EventFilter<EntityEvent, Entity> ENTITY = from(EntityEvent.class, Entity.class, EntityEvent::getEntity);
    EventFilter<PlayerEvent, Player> PLAYER = from(PlayerEvent.class, Player.class, PlayerEvent::getPlayer);
    EventFilter<ItemEvent, ItemStack> ITEM = from(ItemEvent.class, ItemStack.class, ItemEvent::getItemStack);
    /**
     * The instance of an {@link InstanceEvent}. For an {@link EntityInstanceEvent} whose entity is in
     * no instance (a player still in the configuration phase, or disconnecting before it spawned) the
     * handler is null, so no instance mapped node sees that event; a predicate node built on this
     * filter gets null for it.
     */
    EventFilter<InstanceEvent, Instance> INSTANCE = from(InstanceEvent.class, Instance.class,
            e -> e instanceof EntityInstanceEvent entityEvent && entityEvent.getEntity().getInstance() == null
                    ? null : e.getInstance());
    EventFilter<InventoryEvent, AbstractInventory> INVENTORY = from(InventoryEvent.class, AbstractInventory.class, InventoryEvent::getInventory);
    EventFilter<BlockEvent, Block> BLOCK = from(BlockEvent.class, Block.class, BlockEvent::getBlock);

    static <E extends Event, H> EventFilter<E, H> from(Class<E> eventType,
                                                       @Nullable Class<H> handlerType,
                                                       @Nullable Function<E, H> handlerGetter) {
        return new EventFilter<>() {
            @Override
            public @Nullable H getHandler(E event) {
                return handlerGetter != null ? handlerGetter.apply(event) : null;
            }

            @Override
            public Class<E> eventType() {
                return eventType;
            }

            @Override
            public @Nullable Class<H> handlerType() {
                return handlerType;
            }
        };
    }

    /**
     * Gets the handler for the given event instance, or null if the event
     * type has no handler.
     *
     * @param event The event instance
     * @return The handler, if it exists for the given event
     */
    @Nullable H getHandler(E event);

    @ApiStatus.Internal
    @SuppressWarnings("unchecked")
    default @Nullable H castHandler(Object event) {
        return getHandler((E) event);
    }

    /**
     * The event type to filter on.
     *
     * @return The event type.
     */
    Class<E> eventType();

    /**
     * The type returned by {@link #getHandler(Event)}.
     *
     * @return the handler type, null if not any
     */
    @Nullable Class<H> handlerType();
}
