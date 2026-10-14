package io.github.bearisbug.synaxis.game.client.farview;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The player's far view setting of {@code docs/DESIGN-M3.md} {@code API-M3-015} (客户端配置):
 * {@value #KEY} in {@code <gameDir>/config/}{@value #FILE}, written with its comments at the client's start
 * when it is not there and read again at every announcement, so a change takes effect on the next
 * connection.
 *
 * @param radius the far view radius in blocks: {@value #DEFAULT_RADIUS}, {@value #LOW_RADIUS} or
 *               {@value #OFF}
 */
public record FarViewSettings(int radius) {

    private static final Logger LOGGER = LoggerFactory.getLogger("synaxis");

    /** The file under the game's config directory. */
    public static final String FILE = "synaxis-client.properties";
    /** The key of the radius. */
    public static final String KEY = "farview.radius";
    /** The default radius, Q6 of {@code docs/DESIGN-M3.md}. */
    public static final int DEFAULT_RADIUS = 4096;
    /** The radius of the low tier. */
    public static final int LOW_RADIUS = 2048;
    /** No far view. */
    public static final int OFF = 0;

    private static final String DEFAULT_FILE = """
            # Synaxis client settings.
            # farview.radius: how far the far view reaches, in blocks.
            #   4096 (default), 2048 for slower machines, 0 turns the far view off.
            #   Distant Horizons 3.3.2 draws it; without that mod there is only the normal view distance.
            farview.radius=4096
            """;

    /**
     * Whether there is a far view at all.
     *
     * @return {@code radius != 0}
     */
    public boolean on() {
        return radius != OFF;
    }

    /**
     * The radius in chunks, what Distant Horizons' {@code chunkRenderDistance} takes.
     *
     * @return {@code radius / 16}
     */
    public int chunkRenderDistance() {
        return radius >> 4;
    }

    /**
     * Reads the setting, writing the default file with its comments when there is none. A value
     * other than the three is taken as {@value #DEFAULT_RADIUS} with a WARN, and so is a file that
     * cannot be read.
     *
     * @param configDir the game's config directory
     * @return the setting
     */
    public static FarViewSettings load(Path configDir) {
        final Path file = configDir.resolve(FILE);
        if (!Files.exists(file)) {
            try {
                Files.createDirectories(configDir);
                Files.writeString(file, DEFAULT_FILE, StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOGGER.warn("{}could not write the default {}: {}", FarViewClient.PREFIX, file, e.toString());
            }
            return new FarViewSettings(DEFAULT_RADIUS);
        }
        final Properties properties = new Properties();
        // As bytes (ISO-8859-1, the Properties rule): the key and its values are ASCII, and a comment saved in another
        // encoding than UTF-8 does not void the file
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.warn("{}could not read {}, taking {} {}: {}", FarViewClient.PREFIX, file, KEY, DEFAULT_RADIUS, e.toString());
            return new FarViewSettings(DEFAULT_RADIUS);
        }
        final String value = properties.getProperty(KEY);
        if (value == null) return new FarViewSettings(DEFAULT_RADIUS);
        switch (value.trim()) {
            case "4096":
                return new FarViewSettings(DEFAULT_RADIUS);
            case "2048":
                return new FarViewSettings(LOW_RADIUS);
            case "0":
                return new FarViewSettings(OFF);
            default:
                LOGGER.warn("{}{}={} in {} is not 4096, 2048 or 0; taking {}", FarViewClient.PREFIX, KEY, value, file,
                        DEFAULT_RADIUS);
                return new FarViewSettings(DEFAULT_RADIUS);
        }
    }
}
