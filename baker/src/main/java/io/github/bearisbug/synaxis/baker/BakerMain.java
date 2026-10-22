package io.github.bearisbug.synaxis.baker;

import io.github.bearisbug.synaxis.instance.block.BlockManager;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.synx.SynxException;
import io.github.bearisbug.synaxis.synx.SynxHandlers;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The baker's command line, API-M3-003:
 * <pre>
 * bake --map-id &lt;id&gt; --source anvil:&lt;dir&gt;|generator:&lt;preset&gt;:&lt;seed&gt; --out &lt;maps_root&gt;
 *      [--threads N] [--bounds minX,minZ,maxX,maxZ] [--dimension minecraft:overworld] [--min-y -64] [--logs logs] [--samples 4]
 * verify --build &lt;maps_root&gt;/&lt;map_id&gt;/&lt;build_id&gt;
 * </pre>
 * The exit code is the bake's (API-M3-003); a command line that does not parse, two handlers with one key, or a
 * block handler service that does not load, exit with 1. Not parsing includes an option the command does not take
 * or one given twice, a number or a key that is not one, and a map id other than {@code [a-z0-9][a-z0-9_-]{0,31}}
 * (§9). Handler namespaces are those {@link SynxHandlers#registerAll} registers, the same list a shard registers;
 * generator presets are the {@link BakeGeneratorProvider}s.
 */
public final class BakerMain {
    static final int EXIT_USAGE = 1;
    /** {@code map_id} of §9: it names directories and starts the build_id. */
    private static final Pattern MAP_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
    /** The options each command takes. */
    private static final Map<String, Set<String>> OPTIONS = Map.of(
            "bake", Set.of("map-id", "source", "out", "threads", "bounds", "dimension", "min-y", "logs", "samples"),
            "verify", Set.of("build"));

    private BakerMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        if (args.length == 0) return usage("no command");
        final Set<String> known = OPTIONS.get(args[0]);
        if (known == null) return usage("unknown command " + args[0]);
        final Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) return usage("expected --option value at " + args[i]);
            final String name = args[i].substring(2);
            if (!known.contains(name)) return usage("unknown option " + args[i] + " for " + args[0]);
            if (options.put(name, args[i + 1]) != null) return usage(args[i] + " is given twice");
        }
        try {
            return switch (args[0]) {
                case "bake" -> bake(options);
                case "verify" -> verify(options);
                default -> throw new IllegalStateException("a command of OPTIONS without a case: " + args[0]);
            };
        } catch (IllegalArgumentException e) {
            return usage(e.getMessage());
        }
    }

    private static int bake(Map<String, String> options) {
        final String mapId = required(options, "map-id");
        if (!MAP_ID.matcher(mapId).matches()) {
            throw new IllegalArgumentException("--map-id is " + MAP_ID.pattern() + ", a directory name, got '" + mapId
                    + "'");
        }
        final String sourceSpec = required(options, "source");
        final Path out = Path.of(required(options, "out"));
        final int threads = options.containsKey("threads") ? integer("threads", options.get("threads"))
                : Runtime.getRuntime().availableProcessors();
        final int samples = options.containsKey("samples") ? integer("samples", options.get("samples")) : 4;
        final SynxManifest.Bounds bounds = options.containsKey("bounds") ? bounds(options.get("bounds")) : null;
        final Registries registries = Registries.vanilla();
        final BlockManager handlers = new BlockManager();
        try {
            SynxHandlers.registerAll(handlers);
        } catch (IllegalArgumentException e) {
            System.err.println("baker: " + e.getMessage());
            return EXIT_USAGE;
        }

        final BakeSource source;
        try {
            source = source(sourceSpec, bounds, options.get("dimension"), options.get("min-y"), registries, handlers);
        } catch (IOException e) {
            System.err.println("baker: " + Baker.describe(e));
            return Baker.EXIT_SOURCE;
        }
        try (source) {
            final Baker.Result result = Baker.bake(new Baker.Request(mapId, source, out, threads,
                    Path.of(options.getOrDefault("logs", "logs")), registries, handlers::getHandler,
                    LightVersions.verified(), samples, sourceSpec));
            (result.exitCode() == Baker.EXIT_PUBLISHED ? System.out : System.err).println("baker: " + result.message());
            // Exit codes 3 and 4 keep the work directory, a hidden one next to the releases
            if (result.exitCode() != Baker.EXIT_PUBLISHED && result.directory() != null) {
                System.err.println("baker: kept " + result.directory().toAbsolutePath() + " for inspection; delete it when done");
            }
            if (result.manifest() != null) System.out.print(result.manifest().toJson());
            return result.exitCode();
        } catch (IOException e) {
            System.err.println("baker: closing the source: " + Baker.describe(e));
            return Baker.EXIT_SOURCE;
        }
    }

    private static BakeSource source(String spec, SynxManifest.@Nullable Bounds bounds, @Nullable String dimension,
                                     @Nullable String minY, Registries registries, BlockManager handlers) throws IOException {
        if (spec.startsWith("anvil:")) {
            final Key key;
            try {
                key = Key.key(dimension == null ? "minecraft:overworld" : dimension);
            } catch (InvalidKeyException e) {
                throw new IllegalArgumentException("--dimension is a key such as minecraft:overworld, got " + dimension, e);
            }
            return AnvilSource.open(Path.of(spec.substring("anvil:".length())), key, bounds,
                    minY == null ? AnvilSource.DEFAULT_MIN_Y : integer("min-y", minY), registries,
                    namespace -> handlers.getHandler(namespace) != null);
        }
        if (minY != null) throw new IllegalArgumentException("--min-y applies to an anvil: source only");
        if (dimension != null) throw new IllegalArgumentException("--dimension applies to an anvil: source only");
        if (spec.startsWith("generator:")) {
            final String[] parts = spec.split(":", -1);
            if (parts.length != 3) throw new IllegalArgumentException("generator:<preset>:<seed>, got " + spec);
            final long seed;
            try {
                seed = Long.parseLong(parts[2]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("generator:<preset>:<seed> takes a whole number seed, got " + spec, e);
            }
            for (BakeGeneratorProvider provider : ServiceLoader.load(BakeGeneratorProvider.class)) {
                GeneratorPreset preset = provider.preset(parts[1], seed);
                if (preset == null) continue;
                if (bounds != null) preset = new GeneratorPreset(preset.generator(), preset.minY(), preset.height(), bounds);
                return new GeneratorSource(preset, registries);
            }
            throw new IOException("no generator preset " + parts[1]);
        }
        throw new IllegalArgumentException("--source is anvil:<dir> or generator:<preset>:<seed>, got " + spec);
    }

    /**
     * {@code verify --build}: what a shard checks at start-up ({@link Baker#checkDigests}), over the whole build.
     * Unlike a bake's own verification it does not recompute region_content_hash from the records or look for a
     * record of every chunk, and it bakes nothing again (API-M3-003).
     */
    private static int verify(Map<String, String> options) {
        final Path build = Path.of(required(options, "build"));
        try {
            final SynxManifest manifest = SynxManifest.fromJson(Files.readString(build.resolve(SynxManifest.FILE_NAME)));
            Baker.checkDigests(build, manifest, Registries.vanilla());
            System.out.println("baker: " + build + " verified");
            return Baker.EXIT_PUBLISHED;
        } catch (SynxException e) {
            System.err.println("baker: " + e.getMessage());
            return Baker.EXIT_VERIFY;
        } catch (IOException e) {
            System.err.println("baker: " + Baker.describe(e));
            return Baker.EXIT_VERIFY;
        }
    }

    private static SynxManifest.Bounds bounds(String text) {
        final String[] parts = text.split(",", -1);
        if (parts.length != 4) throw new IllegalArgumentException("--bounds minX,minZ,maxX,maxZ, got " + text);
        return new SynxManifest.Bounds(integer("bounds", parts[0].strip()), integer("bounds", parts[1].strip()),
                integer("bounds", parts[2].strip()), integer("bounds", parts[3].strip()));
    }

    /** The whole number an option gives; the refusal names the option. */
    private static int integer(String option, String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + option + " takes whole numbers, got " + text, e);
        }
    }

    private static String required(Map<String, String> options, String name) {
        final String value = options.get(name);
        if (value == null) throw new IllegalArgumentException("missing --" + name);
        return value;
    }

    private static int usage(String problem) {
        System.err.println("baker: " + problem);
        System.err.println("usage: bake --map-id <id: " + MAP_ID.pattern() + "> --source anvil:<dir>|generator:<preset>:<seed>"
                + " --out <maps_root> [--threads N (default: the cores)] [--bounds minX,minZ,maxX,maxZ (chunks, inclusive)]"
                + " [--dimension <key> (anvil only, default minecraft:overworld)]"
                + " [--min-y <y> (anvil only, a multiple of 16, default -64)] [--logs <dir> (default logs)]"
                + " [--samples <chunks per region> (default 4)]");
        System.err.println("       verify --build <maps_root>/<map_id>/<build_id>");
        return EXIT_USAGE;
    }
}
