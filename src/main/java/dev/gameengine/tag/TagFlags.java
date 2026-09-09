package dev.gameengine.tag;

/**
 * The NBT serialization switches of this package.
 * <p>
 * They are JVM-wide rather than per process: a {@link TagHandler} is created by entities, items and
 * blocks that carry no reference to a server process, and the values decide how the same NBT is
 * written everywhere in the JVM.
 */
final class TagFlags {

    /** Writes empty compounds instead of dropping them. */
    static final boolean SERIALIZE_EMPTY_COMPOUND =
            Boolean.getBoolean("gameengine.serialization.serialize-empty-nbt-compound");

    /** Caches the compound a tag handler builds until one of its tags changes. */
    static final boolean TAG_HANDLER_CACHE_ENABLED = booleanProperty("gameengine.tag-handler-cache", true);

    private TagFlags() {
    }

    private static boolean booleanProperty(String name, boolean defaultValue) {
        final String value = System.getProperty(name);
        return value != null ? Boolean.parseBoolean(value) : defaultValue;
    }
}
