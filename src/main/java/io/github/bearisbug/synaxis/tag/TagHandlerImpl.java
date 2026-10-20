package io.github.bearisbug.synaxis.tag;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagType;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;

import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

final class TagHandlerImpl implements TagHandler {
    static final Serializers.Entry<Node, CompoundBinaryTag> NODE_SERIALIZER = new Serializers.Entry<>(BinaryTagTypes.COMPOUND, entries -> fromCompound(entries).root, Node::compound, /* isPath= */ true);

    private final Node root;
    private volatile @Nullable Node copy;
    // Bumped by every invalidation so readableCopy can tell whether the snapshot it just built is already stale
    private final AtomicInteger invalidations = new AtomicInteger();

    TagHandlerImpl() {
        this.root = new Node();
    }

    private TagHandlerImpl(TagHandlerImpl source) {
        // The copied nodes must belong to this handler so that their invalidations reach it
        this.root = source.root.copy(this, null);
    }

    private static <T> Entry<T> makeNbtEntry(TagNbtSeparator.Entry<T> nbtEntry) {
        return new Entry<>(nbtEntry.tag(), nbtEntry.value());
    }

    static TagHandlerImpl fromCompound(CompoundBinaryTag compound) {
        TagHandlerImpl handler = new TagHandlerImpl();
        handler.root.updateContent(compound);
        return handler;
    }

    @Override
    public <T> @UnknownNullability T getTag(Tag<T> tag) {
        VarHandle.fullFence();
        return root.getTag(tag);
    }

    @Override
    public <T> void setTag(Tag<T> tag, @Nullable T value) {
        final Node node = setTag0(root, tag, value);
        if (node != null) node.invalidate();
    }

    /**
     * Writes into the tree rooted at {@code root} and returns the node that changed, or {@code null}
     * if nothing did. Invalidation is left to the caller so that a node can be filled before being
     * attached to a tree.
     */
    private <T> @Nullable Node setTag0(Node root, Tag<T> tag, @Nullable T value) {
        TagImpl<T> tagImpl = (TagImpl<T>) tag;
        // Handle view tags
        if (tag.isView()) {
            synchronized (this) {
                Node syncNode = traversePathWrite(root, tag, value != null);
                if (syncNode != null) {
                    syncNode.updateContent(value != null ? (CompoundBinaryTag) tagImpl.entry().write(value) : CompoundBinaryTag.empty());
                }
                return syncNode;
            }
        }
        // Normal tag
        final int tagIndex = tagImpl.index();
        VarHandle.fullFence();
        Node node = traversePathWrite(root, tag, value != null);
        if (node == null)
            return null; // Tried to remove an absent tag. Do nothing
        StaticIntMap<Entry<?>> entries = node.entries;
        if (value != null) {
            Entry<?> previous = entries.get(tagIndex);
            if (previous != null && previous.tag.shareValue(tag)) {
                @SuppressWarnings("unchecked")
                final Entry<T> previousTyped = (Entry<T>) previous;
                previousTyped.updateValue(tag.copyValue(value));
            } else {
                synchronized (this) {
                    node = traversePathWrite(root, tag, true);
                    node.entries.put(tagIndex, valueToEntry(node, tag, value));
                }
            }
        } else {
            synchronized (this) {
                node = traversePathWrite(root, tag, false);
                if (node == null) return null;
                node.entries.remove(tagIndex);
            }
        }
        return node;
    }

    @Override
    public <T> @Nullable T getAndSetTag(Tag<T> tag, @Nullable T value) {
        return updateTag0(tag, _ -> value, true);
    }

    @Override
    public <T> void updateTag(Tag<T> tag, UnaryOperator<@UnknownNullability T> value) {
        updateTag0(tag, value, false);
    }

    @Override
    public <T> @UnknownNullability T updateAndGetTag(Tag<T> tag, UnaryOperator<@UnknownNullability T> value) {
        return updateTag0(tag, value, false);
    }

    @Override
    public <T> @UnknownNullability T getAndUpdateTag(Tag<T> tag, UnaryOperator<@UnknownNullability T> value) {
        return updateTag0(tag, value, true);
    }

    @SuppressWarnings("unchecked")
    private synchronized <T> @UnknownNullability T updateTag0(Tag<T> tag, UnaryOperator<T> value, boolean returnPrevious) {
        TagImpl<T> tagImpl = (TagImpl<T>) tag;
        final Node node = traversePathWrite(root, tag, true);
        if (tag.isView()) {
            final T previousValue = tag.read(node.compound());
            final T newValue = value.apply(previousValue);
            node.updateContent((CompoundBinaryTag) tagImpl.entry().write(newValue));
            node.invalidate();
            return returnPrevious ? previousValue : newValue;
        }

        final int tagIndex = tagImpl.index();
        StaticIntMap<Entry<?>> entries = node.entries;

        final T previousValue = readEntry(entries.get(tagIndex), tagImpl);
        final T newValue = value.apply(previousValue);
        if (newValue != null) entries.put(tagIndex, valueToEntry(node, tag, newValue));
        else entries.remove(tagIndex);

        node.invalidate();
        return returnPrevious ? previousValue : newValue;
    }

    @Override
    public TagReadable readableCopy() {
        Node copy = this.copy;
        if (copy != null) return copy;
        synchronized (this) {
            copy = this.copy;
            if (copy != null) return copy;
            // setTag's fast path mutates and invalidates without holding this monitor, so caching a snapshot
            // unconditionally can overwrite an invalidation and keep serving stale tags forever
            final int stamp = invalidations.get();
            final Node snapshot = root.copy(this, null);
            if (invalidations.get() == stamp) this.copy = snapshot;
            return snapshot;
        }
    }

    @Override
    public synchronized TagHandler copy() {
        return new TagHandlerImpl(this);
    }

    @Override
    public synchronized void updateContent(CompoundBinaryTag compound) {
        this.root.updateContent(compound);
        invalidateCopy();
    }

    /** Drops the cached readable copy. Every change of a value or of the structure has to end here. */
    private void invalidateCopy() {
        invalidations.incrementAndGet();
        copy = null;
    }

    /**
     * Reads the value of an entry with {@code tag}: the value itself when the tag that wrote it shares its value with
     * {@code tag}, otherwise the NBT of the entry read with {@code tag}. A missing entry, NBT of another type and NBT
     * that {@code tag} cannot read are all the default of {@code tag}, as for {@link TagImpl#read}.
     */
    @SuppressWarnings("unchecked")
    private static <T> @UnknownNullability T readEntry(@Nullable Entry<?> entry, TagImpl<T> tag) {
        if (entry == null) return tag.createDefault(); // Not present
        // The tag used to write the entry is compatible with the one used to get, return the value directly
        if (entry.tag.shareValue(tag)) return (T) entry.value;
        // Value must be parsed from nbt if the tag is different
        final BinaryTag nbt = entry.updatedNbt();
        if (nbt == null) return tag.createDefault(); // Transient values have no nbt representation
        final Serializers.Entry<T, BinaryTag> serializerEntry = tag.entry();
        final BinaryTagType<BinaryTag> type = serializerEntry.nbtType();
        if (type != null && !type.equals(nbt.type())) return tag.createDefault();
        try {
            final T value = serializerEntry.read(nbt);
            return value != null ? value : tag.createDefault();
        } catch (ClassCastException _) {
            // A list read with another element type
            return tag.createDefault();
        }
    }

    @Override
    public CompoundBinaryTag asCompound() {
        VarHandle.fullFence();
        return root.compound();
    }

    private static Node traversePathRead(Node node, Tag<?> tag) {
        final TagImpl.PathEntry[] paths = ((TagImpl<?>) tag).path();
        if (paths == null) return node;
        for (var path : paths) {
            final Entry<?> entry = node.entries.get(path.index());
            if (entry == null || (node = entry.toNode()) == null)
                return null;
        }
        return node;
    }

    @Contract("_, _, true -> !null")
    private Node traversePathWrite(Node root, Tag<?> tag,
                                   boolean present) {
        TagImpl<?> tagImpl = (TagImpl<?>) tag;
        final TagImpl.PathEntry[] paths = tagImpl.path();
        if (paths == null) return root;
        Node local = root;
        for (TagImpl.PathEntry path : paths) {
            final int pathIndex = path.index();
            final Entry<?> entry = local.entries.get(pathIndex);
            if (entry != null && entry.tag.entry().isPath()) {
                // Existing path, continue navigating
                final Node tmp = (Node) entry.value;
                assert tmp.parent == local : "Path parent is invalid: " + tmp.parent + " != " + local;
                local = tmp;
            } else {
                if (!present) return null;
                synchronized (this) {
                    var synEntry = local.entries.get(pathIndex);
                    if (synEntry != null && synEntry.tag.entry().isPath()) {
                        // Existing path, continue navigating
                        final Node tmp = (Node) synEntry.value;
                        assert tmp.parent == local : "Path parent is invalid: " + tmp.parent + " != " + local;
                        local = tmp;
                        continue;
                    }

                    // Empty path, create a new handler.
                    // Slow path is taken if the entry comes from a Structure tag, requiring conversion from NBT
                    Node tmp = local;
                    local = new Node(tmp);
                    if (synEntry != null && synEntry.updatedNbt() instanceof CompoundBinaryTag compound) {
                        local.updateContent(compound);
                    }
                    tmp.entries.put(pathIndex, Entry.makePathEntry(path.name(), local));
                }
            }
        }
        return local;
    }

    private <T> Entry<?> valueToEntry(Node parent, Tag<T> tag, T value) {
        if (value instanceof BinaryTag nbt) {
            if (nbt instanceof CompoundBinaryTag compound) {
                final Node node = new Node(parent);
                node.updateEntries(compound);
                return Entry.makePathEntry(tag, node);
            } else {
                return makeNbtEntry(TagNbtSeparator.separateSingle(tag.key(), nbt));
            }
        } else {
            return new Entry<>((TagImpl<? super T>) tag, tag.copyValue(value));
        }
    }

    final class Node implements TagReadable {
        final @Nullable Node parent;
        final StaticIntMap<Entry<?>> entries = new StaticIntMap<>();
        @Nullable CompoundBinaryTag compound;

        Node(@Nullable Node parent) {
            this.parent = parent;
        }

        Node() {
            this(null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> @UnknownNullability T getTag(Tag<T> tag) {
            final Node node = traversePathRead(this, tag);
            if (node == null)
                return tag.createDefault(); // Must be a path-able entry, but not present
            if (tag.isView()) return tag.read(node.compound());

            final TagImpl<T> tagImpl = (TagImpl<T>) tag;
            return readEntry(node.entries.get(tagImpl.index()), tagImpl);
        }

        void updateContent(CompoundBinaryTag compound) {
            updateEntries(compound);
            this.compound = compound;
        }

        /**
         * Replaces the entries of this node with the separated form of {@code compound} without
         * caching it, the conversion being allowed to drop entries such as empty compounds.
         */
        void updateEntries(CompoundBinaryTag compound) {
            // Written into this node directly: nodes converted elsewhere would keep pointing at the
            // tree they were built in, breaking the parent chain invalidation walks
            this.entries.clear();
            TagNbtSeparator.separate(compound, this::write);
        }

        private <T> void write(TagNbtSeparator.Entry<T> entry) {
            setTag0(this, entry.tag(), entry.value());
        }

        CompoundBinaryTag compound() {
            CompoundBinaryTag compound;
            if (!TagFlags.TAG_HANDLER_CACHE_ENABLED || (compound = this.compound) == null) {
                CompoundBinaryTag.Builder tmp = CompoundBinaryTag.builder();
                this.entries.forValues(entry -> {
                    final TagImpl<?> tag = entry.tag;
                    final BinaryTag nbt = entry.updatedNbt();
                    if (nbt != null && (!tag.entry().isPath() || TagFlags.SERIALIZE_EMPTY_COMPOUND || !((CompoundBinaryTag) nbt).isEmpty())) {
                        tmp.put(tag.key(), nbt);
                    }
                });
                this.compound = compound = tmp.build();
            }
            return compound;
        }

        @Contract("_, null -> !null")
        @SuppressWarnings("unchecked")
        @Nullable Node copy(TagHandlerImpl owner, @Nullable Node parent) {
            CompoundBinaryTag.Builder tmp = CompoundBinaryTag.builder();
            Node result = owner.new Node(parent);
            StaticIntMap<Entry<?>> entries = result.entries;
            this.entries.forValues(entry -> {
                TagImpl<?> tag = entry.tag;
                Object value = entry.value;
                BinaryTag nbt;
                if (value instanceof Node node) {
                    Node copy = node.copy(owner, result);
                    if (copy == null)
                        return; // Empty node
                    value = copy;
                    nbt = copy.compound;
                    assert nbt != null : "Node copy should also compute the compound";
                } else {
                    nbt = entry.updatedNbt();
                }

                if (nbt != null)
                    tmp.put(tag.key(), nbt);
                entries.put(tag.index(), owner.valueToEntry(result, (Tag<Object>) tag, value));
            });
            var compound = tmp.build();
            if (!TagFlags.SERIALIZE_EMPTY_COMPOUND && compound.isEmpty() && parent != null)
                return null; // Empty child node
            result.compound = compound;
            return result;
        }

        void invalidate() {
            Node tmp = this;
            do tmp.compound = null;
            while ((tmp = tmp.parent) != null);
            invalidateCopy();
        }

        @Override
        public String toString() {
            return "Node{" +
                    "parent=" + parent +
                    ", entries=" + entries +
                    ", compound=" + compound +
                    '}';
        }
    }

    private static final class Entry<T> {
        private final TagImpl<T> tag;
        T value;
        @Nullable BinaryTag nbt;

        Entry(TagImpl<T> tag, T value) {
            this.tag = tag;
            this.value = value;
        }

        static Entry<?> makePathEntry(String path, Node node) {
            return new Entry<>(TagImpl.tag(path, NODE_SERIALIZER), node);
        }

        static Entry<?> makePathEntry(Tag<?> tag, Node node) {
            return makePathEntry(tag.key(), node);
        }

        @Nullable BinaryTag updatedNbt() {
            if (tag.entry().isPath()) return ((Node) value).compound();
            BinaryTag nbt = this.nbt;
            if (nbt == null) this.nbt = nbt = tag.entry().write(value);
            return nbt;
        }

        void updateValue(T value) {
            assert !tag.entry().isPath();
            this.value = value;
            this.nbt = null;
        }

        @Nullable Node toNode() {
            if (tag.entry().isPath()) return (Node) value;
            if (updatedNbt() instanceof CompoundBinaryTag compound) {
                // Slow path forcing a conversion of the structure to NBTCompound
                // TODO should the handler be cached inside the entry?
                return fromCompound(compound).root;
            }
            // Entry is not path-able
            return null;
        }
    }
}
