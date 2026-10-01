package io.github.bearisbug.synaxis.game.client.gametest.farview;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * Pixel arithmetic over the gametest's screenshots, with the DH spike's judgement of a changed pixel
 * ({@code docs/research/client-spikes-2026-09/dh-iris/README.md}): its largest channel difference is
 * above {@value #THRESHOLD}.
 */
final class Shots {

    /** A pixel changed when one of its channels moved by more than this. */
    static final int THRESHOLD = 24;

    private Shots() {
    }

    /** A region of an image: columns {@code x0..x1 - 1}, rows {@code y0..y1 - 1}. */
    record Box(int x0, int y0, int x1, int y1) {
        static Box whole(BufferedImage image) {
            return new Box(0, 0, image.getWidth(), image.getHeight());
        }

        static Box rows(BufferedImage image, int from, int to) {
            return new Box(0, Math.max(0, from), image.getWidth(), Math.min(image.getHeight(), to));
        }

        static Box centre(BufferedImage image, int side) {
            return new Box(image.getWidth() / 2 - side / 2, image.getHeight() / 2 - side / 2, image.getWidth() / 2 + side / 2,
                    image.getHeight() / 2 + side / 2);
        }

        int pixels() {
            return (x1 - x0) * (y1 - y0);
        }

        @Override
        public String toString() {
            return x0 + "," + y0 + ".." + (x1 - 1) + "," + (y1 - 1);
        }
    }

    static BufferedImage read(Path path) {
        try {
            return ImageIO.read(path.toFile());
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + path, e);
        }
    }

    static int distance(int p, int q) {
        return Math.max(Math.abs((p >> 16 & 255) - (q >> 16 & 255)),
                Math.max(Math.abs((p >> 8 & 255) - (q >> 8 & 255)), Math.abs((p & 255) - (q & 255))));
    }

    /** The share of the box's pixels that changed between two images of the same size. */
    static double changed(BufferedImage a, BufferedImage b, Box box) {
        int changed = 0;
        for (int y = box.y0(); y < box.y1(); y++) {
            for (int x = box.x0(); x < box.x1(); x++) {
                if (distance(a.getRGB(x, y), b.getRGB(x, y)) > THRESHOLD) changed++;
            }
        }
        return (double) changed / box.pixels();
    }

    /** The share of the box's pixels within {@value #THRESHOLD} of one colour. */
    static double near(BufferedImage image, Box box, int rgb) {
        int near = 0;
        for (int y = box.y0(); y < box.y1(); y++) {
            for (int x = box.x0(); x < box.x1(); x++) {
                if (distance(image.getRGB(x, y), rgb) <= THRESHOLD) near++;
            }
        }
        return (double) near / box.pixels();
    }

    /** The mean colour of a box, {@code 0xRRGGBB}. */
    static int mean(BufferedImage image, Box box) {
        long r = 0, g = 0, b = 0;
        for (int y = box.y0(); y < box.y1(); y++) {
            for (int x = box.x0(); x < box.x1(); x++) {
                final int p = image.getRGB(x, y);
                r += p >> 16 & 255;
                g += p >> 8 & 255;
                b += p & 255;
            }
        }
        final int n = box.pixels();
        return (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
    }

    /** Rec. 601 luma of a colour, 0..255. */
    static double luma(int rgb) {
        return 0.299 * (rgb >> 16 & 255) + 0.587 * (rgb >> 8 & 255) + 0.114 * (rgb & 255);
    }

    /** The mean colour of the top 24 rows: sky in every far view shot, all of which look above the horizon. */
    static int sky(BufferedImage image) {
        return mean(image, Box.rows(image, 0, 24));
    }

    /** The standard deviation of the box's luma: the contrast a fog over the terrain takes away. */
    static double lumaSpread(BufferedImage image, Box box) {
        double sum = 0, squares = 0;
        for (int y = box.y0(); y < box.y1(); y++) {
            for (int x = box.x0(); x < box.x1(); x++) {
                final double l = luma(image.getRGB(x, y));
                sum += l;
                squares += l * l;
            }
        }
        final double mean = sum / box.pixels();
        return Math.sqrt(Math.max(0, squares / box.pixels() - mean * mean));
    }

    static String percent(double share) {
        return String.format(Locale.ROOT, "%.2f%%", share * 100);
    }

    static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06x", rgb & 0xFFFFFF);
    }
}
