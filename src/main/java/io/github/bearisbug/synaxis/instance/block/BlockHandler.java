package io.github.bearisbug.synaxis.instance.block;

import net.kyori.adventure.key.Key;
import io.github.bearisbug.synaxis.coordinate.BlockFace;
import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.entity.PlayerHand;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.tag.Tag;
import org.jetbrains.annotations.ApiStatus;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Interface used to provide block behavior. Set with {@link Block#withHandler(BlockHandler)}.
 * <p>
 * Implementations are expected to be thread safe.
 * <p>
 * When and where the callbacks run:
 * <ul>
 *     <li>{@link #onPlace(Placement)} and {@link #onDestroy(Destroy)} run synchronously inside the call setting the
 *     block, on the thread setting it, which holds the write lock of the chunk of the block. Set through the
 *     instance ({@link Instance#setBlock(Point, Block, boolean)}, placing and breaking blocks), that thread holds
 *     the monitor of the instance as well.</li>
 *     <li>Setting a position whose current block has a handler calls that handler's {@code onDestroy}, then the
 *     {@code onPlace} of the handler of the new block, even when only the NBT or the properties of the block
 *     change; {@link Destroy#getNewBlock()} tells the cases apart.</li>
 *     <li>A callback may set blocks of its instance again, the monitor is reentrant. It must not take an entity
 *     another tick thread owns ({@code acquirable().sync(...)} or {@code lock()}) nor wait for a future that is not
 *     done (as {@code loadChunk(...).join()} does): that thread may be waiting for this monitor to set a block of
 *     its own, and the two threads deadlock. Hand an effect on another entity to the thread of that entity with
 *     {@code entity.scheduler().scheduleNextTick(task)}, from a plugin with
 *     {@code PluginScheduler#runOn(entity, task, TaskSchedule.immediate())}.</li>
 *     <li>{@link #isTickable()} is read when the block is set or its chunk is loaded, not on every tick.</li>
 * </ul>
 */
public interface BlockHandler {

    /**
     * Called when a block has been placed.
     * <p>
     * Runs holding the chunk write lock and, set through the instance, the instance monitor: see the class
     * documentation.
     *
     * @param placement the placement details
     */
    default void onPlace(Placement placement) {
    }

    /**
     * Called when a block has been destroyed or replaced.
     * <p>
     * A block is replaced as well when the position is set to the same block with other NBT or properties, the
     * handler of the new block then gets {@link #onPlace(Placement)}. Runs holding the chunk write lock and, set
     * through the instance, the instance monitor: see the class documentation.
     *
     * @param destroy the destroy details
     */
    default void onDestroy(Destroy destroy) {
    }

    /**
     * Handles interactions with this block. Can also block normal item use (containers should block when opening the
     * menu, this prevents the player from placing a block when opening it for instance).
     * <p>
     * Called after the {@code PlayerBlockInteractEvent} of the click, and not at all when that event is cancelled.
     *
     * @param interaction the interaction details
     * @return true to let the click go on to use the held item and place a block, false when this block consumed the
     * click: the held item is not used and no block is placed. Either way, what this method did is kept
     */
    default boolean onInteract(Interaction interaction) {
        return true;
    }

    /**
     * Defines custom behaviour for entities touching this block.
     *
     * @param touch the contact details
     */
    default void onTouch(Touch touch) {
    }

    /**
     * Called once a tick for a block whose handler {@link #isTickable() is tickable}, only while its chunk is within
     * the simulation distance of the instance ({@link Instance#setSimulationDistance(int)}).
     * <p>
     * Runs on the tick thread owning the chunk, without holding the chunk lock.
     *
     * @param tick the tick details
     */
    default void tick(Tick tick) {
    }

    /**
     * Gets if blocks with this handler are ticked, see {@link #tick(Tick)}.
     * <p>
     * Read when a block with this handler is set or its chunk is loaded, not on every tick: a block keeps the answer
     * it got then.
     *
     * @return true to tick the blocks with this handler
     */
    default boolean isTickable() {
        return false;
    }

    /**
     * Specifies which block entity tags should be sent to the player.
     * <p>
     * A block with a handler sends only the tags listed here, each under its path. The default empty list sends
     * nothing, so the tags a vanilla block entity shows (the text of a sign for instance) have to be listed. A block
     * entity without a handler sends its whole NBT, see {@link Block#withTag(Tag, Object)}.
     *
     * @return The list of tags from this block's block entity that should be sent to the player
     * @see <a href="https://minecraft.wiki/w/Block_entity">Block entity on the Minecraft wiki</a>
     */
    default Collection<Tag<?>> getBlockEntityTags() {
        return List.of();
    }

    /**
     * @deprecated the engine never reads it and an override has no effect: the block entity type a client gets is
     * {@link Block#blockEntityType()}; removed in the next breaking release
     */
    @Deprecated(forRemoval = true)
    default byte getBlockEntityAction() {
        return -1;
    }

    /**
     * Gets the id of this handler.
     * <p>
     * It must be the key the handler is registered under with {@link BlockManager#registerHandler}: chunk loaders
     * store it with a block entity and look the handler up by it when the chunk is loaded again.
     *
     * @return the key of this handler
     */
    Key getKey();

    /**
     * Represents an object forwarded to {@link #onPlace(Placement)}.
     */
    sealed class Placement permits PlayerPlacement {
        private final Block block;
        private final Block previousBlock;
        private final Instance instance;
        private final Point blockPosition;

        @ApiStatus.Internal
        public Placement(Block block, Block previousBlock, Instance instance, Point blockPosition) {
            this.block = block;
            this.previousBlock = previousBlock;
            this.instance = instance;
            this.blockPosition = blockPosition;
        }

        public Block getBlock() {
            return block;
        }

        public Block getPreviousBlock() {
            return previousBlock;
        }

        public Instance getInstance() {
            return instance;
        }

        public Point getBlockPosition() {
            return blockPosition;
        }
    }

    final class PlayerPlacement extends Placement {
        private final Player player;
        private final PlayerHand hand;
        private final BlockFace blockFace;
        private final float cursorX, cursorY, cursorZ;

        @ApiStatus.Internal
        public PlayerPlacement(Block block, Block previousBlock, Instance instance, Point blockPosition,
                               Player player, PlayerHand hand, BlockFace blockFace, float cursorX, float cursorY, float cursorZ) {
            super(block, previousBlock, instance, blockPosition);
            this.player = player;
            this.hand = hand;
            this.blockFace = blockFace;
            this.cursorX = cursorX;
            this.cursorY = cursorY;
            this.cursorZ = cursorZ;
        }

        public Player getPlayer() {
            return player;
        }

        public PlayerHand getHand() {
            return hand;
        }

        public BlockFace getBlockFace() {
            return blockFace;
        }

        public float getCursorX() {
            return cursorX;
        }

        public float getCursorY() {
            return cursorY;
        }

        public float getCursorZ() {
            return cursorZ;
        }
    }

    sealed class Destroy permits PlayerDestroy {
        private final Block block;
        private final Block newBlock;
        private final Instance instance;
        private final Point blockPosition;

        @ApiStatus.Internal
        public Destroy(Block block, Block newBlock, Instance instance, Point blockPosition) {
            this.block = block;
            this.newBlock = newBlock;
            this.instance = instance;
            this.blockPosition = blockPosition;
        }

        public Block getBlock() {
            return block;
        }

        public Block getNewBlock() {
            return newBlock;
        }

        public Instance getInstance() {
            return instance;
        }

        public Point getBlockPosition() {
            return blockPosition;
        }
    }

    final class PlayerDestroy extends Destroy {
        private final Player player;

        @ApiStatus.Internal
        public PlayerDestroy(Block block, Block newBlock, Instance instance, Point blockPosition, Player player) {
            super(block, newBlock, instance, blockPosition);
            this.player = player;
        }

        public Player getPlayer() {
            return player;
        }
    }

    final class Interaction {
        private final Block block;
        private final Instance instance;
        private final BlockFace blockFace;
        private final Point blockPosition;
        private final Point cursorPosition;
        private final Player player;
        private final PlayerHand hand;

        @ApiStatus.Internal
        public Interaction(Block block, Instance instance, BlockFace blockFace, Point blockPosition, Point cursorPosition, Player player, PlayerHand hand) {
            this.block = block;
            this.instance = instance;
            this.blockFace = blockFace;
            this.blockPosition = blockPosition;
            this.cursorPosition = cursorPosition;
            this.player = player;
            this.hand = hand;
        }

        public Block getBlock() {
            return block;
        }

        public Instance getInstance() {
            return instance;
        }

        public BlockFace getBlockFace() {
            return blockFace;
        }

        public Point getBlockPosition() {
            return blockPosition;
        }

        public Point getCursorPosition() {
            return cursorPosition;
        }

        public Player getPlayer() {
            return player;
        }

        public PlayerHand getHand() {
            return hand;
        }
    }

    final class Touch {
        private final Block block;
        private final Instance instance;
        private final Point blockPosition;
        private final Entity touching;

        @ApiStatus.Internal
        public Touch(Block block, Instance instance, Point blockPosition, Entity touching) {
            this.block = block;
            this.instance = instance;
            this.blockPosition = blockPosition;
            this.touching = touching;
        }

        public Block getBlock() {
            return block;
        }

        public Instance getInstance() {
            return instance;
        }

        public Point getBlockPosition() {
            return blockPosition;
        }

        public Entity getTouching() {
            return touching;
        }
    }

    final class Tick {
        private final Block block;
        private final Instance instance;
        private final Point blockPosition;

        @ApiStatus.Internal
        public Tick(Block block, Instance instance, Point blockPosition) {
            this.block = block;
            this.instance = instance;
            this.blockPosition = blockPosition;
        }

        public Block getBlock() {
            return block;
        }

        public Instance getInstance() {
            return instance;
        }

        public Point getBlockPosition() {
            return blockPosition;
        }
    }

    /**
     * Handler used for loaded blocks with unknown namespace
     * in order to do not lose the information while saving, and for runtime debugging purpose.
     */
    @ApiStatus.Internal
    final class Dummy implements BlockHandler {
        private static final Map<String, BlockHandler> DUMMY_CACHE = new ConcurrentHashMap<>();

        public static BlockHandler get(String namespace) {
            return DUMMY_CACHE.computeIfAbsent(namespace, Dummy::new);
        }

        private final Key key;

        private Dummy(String name) {
            key = Key.key(name);
        }

        @Override
        public Key getKey() {
            return key;
        }
    }
}
