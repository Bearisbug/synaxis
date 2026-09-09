package dev.gameengine;

/**
 * Switches that apply to the whole JVM instead of to a single {@link ServerProcess}.
 *
 * <p>Each of these is read where no process can be reached: from a static initializer that builds a
 * protocol codec, a shared buffer pool or an Adventure service provider, or from an object that is
 * created before it is bound to a process. Making them per-process would mean rebuilding those
 * structures per process, which is out of scope here. Everything else lives in {@link ServerConfig}.</p>
 *
 * <p>Three more JVM-wide switches are owned by the package that reads them, so that the lower level
 * packages do not have to depend on this one: {@code tag.TagFlags} (NBT serialization),
 * {@code thread.AcquirableImpl} (ownership assertions) and
 * {@code utils.collection.ConcurrentMessageQueues} (queue implementation).</p>
 */
public final class JvmFlags {

    /**
     * The server tick rate. JVM-wide because {@code timer.Tick#SERVER_TICKS} is a
     * {@link java.time.temporal.TemporalUnit} constant derived from it.
     */
    public static final int SERVER_TICKS_PER_SECOND = intProperty("gameengine.tps", 20);

    /**
     * Translates components server-side. JVM-wide because the Adventure {@code ComponentFlattener}
     * service provider is a JVM singleton; letting the packet path disagree with it would translate
     * the same component differently depending on the code path.
     */
    public static final boolean AUTOMATIC_COMPONENT_TRANSLATION = booleanProperty("gameengine.automatic-component-translation", false);

    /** Groups packets sent to several players into one shared buffer. */
    public static final boolean GROUPED_PACKET = booleanProperty("gameengine.grouped-packet", true);

    /**
     * Caches the framed body of a packet. JVM-wide because cached packets are built in field
     * initializers of objects that have no process yet (an entity, a chunk, a registry).
     */
    public static final boolean CACHED_PACKET = booleanProperty("gameengine.cached-packet", true);

    /** Largest packet the engine writes or reads, three bytes of var-int. */
    public static final int MAX_PACKET_SIZE = intProperty("gameengine.max-packet-size", 2_097_151);

    /** Largest packet accepted before the connection reaches the configuration state. */
    public static final int MAX_PACKET_SIZE_PRE_AUTH = intProperty("gameengine.max-packet-size-pre-auth", 8_192);

    /** Warns when a packet was not fully read, from the static reading path. */
    public static final boolean WARN_UNREAD_BYTES_PACKET = booleanProperty("gameengine.warn-unread-bytes-packet", true);

    /**
     * Allows an event node to have several parents. JVM-wide because event nodes are built by static
     * factories with no process in sight.
     */
    public static final boolean EVENT_NODE_ALLOW_MULTIPLE_PARENTS = booleanProperty("gameengine.event.multiple-parents", false);

    /**
     * Compiles buffer templates instead of interpreting them. JVM-wide because every packet builds
     * its template in a static initializer.
     */
    public static final boolean TEMPLATE_COMPILER = booleanProperty("gameengine.template-compiler", false);

    /**
     * Maximum nesting depth of a binary tag. JVM-wide because the NBT codec is a static constant.
     * Binary tags are read and written recursively, so raising this can overflow the java stack.
     */
    public static final int NBT_MAX_DEPTH = intProperty("gameengine.nbt.max-depth", 512, 1, Integer.MAX_VALUE);

    /**
     * Renders every flag as {@code name=value}, for the boot log of a launcher.
     */
    public static String describe() {
        return "JvmFlags[serverTicksPerSecond=" + SERVER_TICKS_PER_SECOND
                + ", automaticComponentTranslation=" + AUTOMATIC_COMPONENT_TRANSLATION
                + ", groupedPacket=" + GROUPED_PACKET
                + ", cachedPacket=" + CACHED_PACKET
                + ", maxPacketSize=" + MAX_PACKET_SIZE
                + ", maxPacketSizePreAuth=" + MAX_PACKET_SIZE_PRE_AUTH
                + ", warnUnreadBytesPacket=" + WARN_UNREAD_BYTES_PACKET
                + ", eventNodeAllowMultipleParents=" + EVENT_NODE_ALLOW_MULTIPLE_PARENTS
                + ", templateCompiler=" + TEMPLATE_COMPILER
                + ", nbtMaxDepth=" + NBT_MAX_DEPTH
                + ']';
    }

    private JvmFlags() {
    }

    private static boolean booleanProperty(String name, boolean defaultValue) {
        final String value = System.getProperty(name);
        return value != null ? Boolean.parseBoolean(value) : defaultValue;
    }

    private static int intProperty(String name, int defaultValue) {
        return intProperty(name, defaultValue, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    private static int intProperty(String name, int defaultValue, int minValue, int maxValue) {
        final String property = System.getProperty(name);
        final int value;
        if (property == null) {
            value = defaultValue;
        } else {
            try {
                value = Integer.parseInt(property);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Property '" + name + "' is not an integer: " + property, e);
            }
        }
        if (value < minValue || value > maxValue) {
            throw new IllegalArgumentException(String.format(
                    "Property '%s' value must be in range [%d..%d] but was %d", name, minValue, maxValue, value));
        }
        return value;
    }
}
