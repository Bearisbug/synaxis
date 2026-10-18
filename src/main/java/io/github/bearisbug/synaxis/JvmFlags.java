package io.github.bearisbug.synaxis;

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
     * The server tick rate, 1 to 1000 ticks a second that divide 1000: {@code timer.Tick#SERVER_TICKS}
     * counts a tick in whole milliseconds. JVM-wide because that is a
     * {@link java.time.temporal.TemporalUnit} constant derived from it.
     */
    public static final int SERVER_TICKS_PER_SECOND = tickRate("synaxis.tps", 20);

    /**
     * Translates components server-side. JVM-wide because the Adventure {@code ComponentFlattener}
     * service provider is a JVM singleton; letting the packet path disagree with it would translate
     * the same component differently depending on the code path.
     */
    public static final boolean AUTOMATIC_COMPONENT_TRANSLATION = booleanProperty("synaxis.automatic-component-translation", false);

    /** Groups packets sent to several players into one shared buffer. */
    public static final boolean GROUPED_PACKET = booleanProperty("synaxis.grouped-packet", true);

    /**
     * Caches the framed body of a packet. JVM-wide because cached packets are built in field
     * initializers of objects that have no process yet (an entity, a chunk, a registry).
     */
    public static final boolean CACHED_PACKET = booleanProperty("synaxis.cached-packet", true);

    /**
     * Largest packet the engine writes or reads. At most 2,097,151, what the three bytes of var-int
     * the length of a frame is written in hold; at least the initial size of a pooled buffer, which
     * the write path doubles up to this.
     */
    public static final int MAX_PACKET_SIZE = intProperty("synaxis.max-packet-size", 2_097_151,
            ServerConfig.DEFAULT_POOLED_BUFFER_SIZE, 2_097_151);

    /**
     * Largest packet accepted before the connection reaches the configuration state, 1,024 (room for
     * the handshake and the encryption response) to {@link #MAX_PACKET_SIZE}.
     */
    public static final int MAX_PACKET_SIZE_PRE_AUTH = intProperty("synaxis.max-packet-size-pre-auth", 8_192,
            1_024, MAX_PACKET_SIZE);

    /**
     * Warns when a packet was not fully read, from the static reading path. A client decides how
     * often that happens, so the report is throttled to one line per 10 seconds.
     */
    public static final boolean WARN_UNREAD_BYTES_PACKET = booleanProperty("synaxis.warn-unread-bytes-packet", true);

    /**
     * Event nodes always have at most one parent; this constant is no longer read and will be removed.
     * Setting {@code synaxis.event.multiple-parents} only logs a warning at the start
     * ({@link ServerConfig#fromSystemProperties()}).
     *
     * @deprecated nothing reads it, there is no replacement
     */
    @Deprecated(forRemoval = true)
    public static final boolean EVENT_NODE_ALLOW_MULTIPLE_PARENTS = false;

    /**
     * Compiles buffer templates instead of interpreting them. JVM-wide because every packet builds
     * its template in a static initializer.
     */
    public static final boolean TEMPLATE_COMPILER = booleanProperty("synaxis.template-compiler", false);

    /**
     * Maximum nesting depth of a binary tag. JVM-wide because the NBT codec is a static constant.
     * Binary tags are read and written recursively, so raising this can overflow the java stack.
     */
    public static final int NBT_MAX_DEPTH = intProperty("synaxis.nbt.max-depth", 512, 1, Integer.MAX_VALUE);

    /**
     * Largest decoded binary tag accepted from a client, in approximate heap bytes rather than in
     * encoded bytes: a compact encoding can expand into far more objects than it occupies on the
     * wire. JVM-wide for the same reason as {@link #NBT_MAX_DEPTH}. Only untrusted reads are
     * charged against it; what the server writes and reads back is never limited.
     */
    public static final int NBT_MAX_BYTES = intProperty("synaxis.nbt.max-bytes", 2_097_152, 1, Integer.MAX_VALUE);

    /**
     * The zlib level packets are compressed with, 0 (stored) to 9. JVM-wide because the deflaters
     * sit in one static pool that every process shares. 6 is the zlib default, which is what a
     * deflater built without a level uses.
     */
    public static final int COMPRESSION_LEVEL = intProperty("synaxis.compression-level", 6, 0, 9);

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
                + ", templateCompiler=" + TEMPLATE_COMPILER
                + ", nbtMaxDepth=" + NBT_MAX_DEPTH
                + ", nbtMaxBytes=" + NBT_MAX_BYTES
                + ", compressionLevel=" + COMPRESSION_LEVEL
                + ']';
    }

    private JvmFlags() {
    }

    private static boolean booleanProperty(String name, boolean defaultValue) {
        final String value = System.getProperty(name);
        return value != null ? Boolean.parseBoolean(value) : defaultValue;
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

    private static int tickRate(String name, int defaultValue) {
        final int tps = intProperty(name, defaultValue, 1, 1000);
        if (1000 % tps != 0) {
            throw new IllegalArgumentException(String.format(
                    "Property '%s' value must divide 1000, a tick lasts a whole number of milliseconds, but was %d",
                    name, tps));
        }
        return tps;
    }
}
