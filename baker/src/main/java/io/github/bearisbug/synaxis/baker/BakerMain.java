package io.github.bearisbug.synaxis.baker;

import io.github.bearisbug.synaxis.instance.block.BlockManager;
import io.github.bearisbug.synaxis.instance.light.LightEngine;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.synx.SynxBuildVerifier;
import io.github.bearisbug.synaxis.synx.SynxException;
import io.github.bearisbug.synaxis.synx.SynxExpectations;
import io.github.bearisbug.synaxis.synx.SynxHandlers;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * The baker's command line, API-M3-003:
 * <pre>
 * bake --map-id &lt;id&gt; --source anvil:&lt;dir&gt;|generator:&lt;preset&gt;:&lt;seed&gt; --out &lt;maps_root&gt;
 *      [--threads N] [--bounds minX,minZ,maxX,maxZ] [--dimension minecraft:overworld] [--min-y -64] [--logs logs] [--samples 4]
 * verify --build &lt;maps_root&gt;/&lt;map_id&gt;/&lt;build_id&gt;
 * </pre>
 * The exit code is the bake's (API-M3-003); a command line that does not parse, or two handlers with one key,
 * exit with 1. Handler namespaces are those {@link SynxHandlers#registerAll} registers, the same list a shard
 * registers; generator presets are the {@link BakeGeneratorProvider}s.
 */
public final class BakerMain {
    static final int EXIT_USAGE = 1;

    private BakerMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        if (args.length == 0) return usage("no command");
        final Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) return usage("expected --option value at " + args[i]);
            options.put(args[i].substring(2), args[i + 1]);
        }
        try {
            return switch (args[0]) {
                case "bake" -> bake(options);
                case "verify" -> verify(options);
                default -> usage("unknown command " + args[0]);
            };
        } catch (IllegalArgumentException e) {
            return usage(e.getMessage());
        }
    }

    private static int bake(Map<String, String> options) {
        final String mapId = required(options, "map-id");
        final String sourceSpec = required(options, "source");
        final Path out = Path.of(required(options, "out"));
        final int threads = Integer.parseInt(options.getOrDefault("threads",
                String.valueOf(Runtime.getRuntime().availableProcessors())));
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
            source = source(sourceSpec, bounds, Key.key(options.getOrDefault("dimension", "minecraft:overworld")),
                    options.get("min-y"), registries, handlers);
        } catch (IOException e) {
            System.err.println("baker: " + e.getMessage());
            return Baker.EXIT_SOURCE;
        }
        try (source) {
            final Baker.Result result = Baker.bake(new Baker.Request(mapId, source, out, threads,
                    Path.of(options.getOrDefault("logs", "logs")), registries, handlers::getHandler,
                    LightVersions.verified(), Integer.parseInt(options.getOrDefault("samples", "4"))));
            (result.exitCode() == Baker.EXIT_PUBLISHED ? System.out : System.err).println("baker: " + result.message());
            if (result.manifest() != null) System.out.print(result.manifest().toJson());
            return result.exitCode();
        } catch (IOException e) {
            System.err.println("baker: closing the source: " + e.getMessage());
            return Baker.EXIT_SOURCE;
        }
    }

    private static BakeSource source(String spec, SynxManifest.@Nullable Bounds bounds, Key dimension,
                                     @Nullable String minY, Registries registries, BlockManager handlers) throws IOException {
        if (spec.startsWith("anvil:")) {
            return AnvilSource.open(Path.of(spec.substring("anvil:".length())), dimension, bounds,
                    minY == null ? AnvilSource.DEFAULT_MIN_Y : Integer.parseInt(minY), registries,
                    namespace -> handlers.getHandler(namespace) != null);
        }
        if (minY != null) throw new IllegalArgumentException("--min-y applies to an anvil: source only");
        if (spec.startsWith("generator:")) {
            final String[] parts = spec.split(":", -1);
            if (parts.length != 3) throw new IllegalArgumentException("generator:<preset>:<seed>, got " + spec);
            final long seed = Long.parseLong(parts[2]);
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

    /** The digest half of {@code verify}: what a shard checks at start-up, over the whole build. */
    private static int verify(Map<String, String> options) {
        final Path build = Path.of(required(options, "build"));
        try {
            final SynxManifest manifest = SynxManifest.fromJson(Files.readString(build.resolve(SynxManifest.FILE_NAME)));
            SynxBuildVerifier.verify(build, SynxExpectations.of(Registries.vanilla(), Baker.COMPRESSION_THRESHOLD,
                    manifest.world().minY(), manifest.world().height(), LightEngine.VERSION), manifest.bounds());
            System.out.println("baker: " + build + " verified");
            return Baker.EXIT_PUBLISHED;
        } catch (IOException | SynxException e) {
            System.err.println("baker: " + e.getMessage());
            return Baker.EXIT_VERIFY;
        }
    }

    private static SynxManifest.Bounds bounds(String text) {
        final String[] parts = text.split(",", -1);
        if (parts.length != 4) throw new IllegalArgumentException("--bounds minX,minZ,maxX,maxZ, got " + text);
        return new SynxManifest.Bounds(Integer.parseInt(parts[0].strip()), Integer.parseInt(parts[1].strip()),
                Integer.parseInt(parts[2].strip()), Integer.parseInt(parts[3].strip()));
    }

    private static String required(Map<String, String> options, String name) {
        final String value = options.get(name);
        if (value == null) throw new IllegalArgumentException("missing --" + name);
        return value;
    }

    private static int usage(String problem) {
        System.err.println("baker: " + problem);
        System.err.println("usage: bake --map-id <id> --source anvil:<dir>|generator:<preset>:<seed> --out <maps_root>"
                + " [--threads N] [--bounds minX,minZ,maxX,maxZ] [--dimension <key>] [--min-y <y>] [--logs <dir>] [--samples N]");
        System.err.println("       verify --build <maps_root>/<map_id>/<build_id>");
        return EXIT_USAGE;
    }
}
