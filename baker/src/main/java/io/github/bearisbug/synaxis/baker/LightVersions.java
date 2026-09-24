package io.github.bearisbug.synaxis.baker;

import io.github.bearisbug.synaxis.instance.light.LightEngine;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;

/**
 * The {@code LightEngine.VERSION} values that passed the vanilla light parity test: the engine resource
 * {@code verified-light-versions.txt}, one {@code <version> <fingerprint>} per line, which
 * {@code LightEngineVersionTest} binds to the engine's output (§11.2, §24).
 */
public final class LightVersions {
    private LightVersions() {
    }

    public static Set<Integer> verified() {
        try (InputStream in = LightEngine.class.getResourceAsStream("verified-light-versions.txt")) {
            if (in == null) throw new IllegalStateException("the engine has no verified-light-versions.txt");
            final Set<Integer> versions = new TreeSet<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                if (line.isBlank() || line.startsWith("#")) continue;
                versions.add(Integer.parseInt(line.strip().split("\\s+", -1)[0]));
            }
            return Set.copyOf(versions);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
