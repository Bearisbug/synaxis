package io.github.bearisbug.synaxis.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.bearisbug.synaxis.app.ConfigFile;
import io.github.bearisbug.synaxis.app.metrics.HostMetrics;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The config key gate ({@code docs/DESIGN-PROD.md} §24, {@code REQ-PROD-033},
 * {@code API-PROD-023}): {@link ConfigFile#KEYS}, the keys {@code synaxis.properties} takes, is
 * exactly the set of {@code synaxis.*} properties the code of this repository reads. A key read
 * somewhere but missing from the table would be refused in the file although the command line
 * takes it; a key of the table nothing reads any more would be taken and do nothing.
 * <p>
 * The keys the code reads are the string constants of the shape {@code synaxis.<name>} in the main
 * classes on this classpath, leaving out {@link ConfigFile} itself, whose table names every key, and
 * the meter names of the host; plus the {@code ${synaxis.<name>}} the host's {@code logback.xml}
 * substitutes. A key the code builds at run time is not seen. It lives here because only this
 * project has the engine, the host and the plugins on one classpath.
 */
public class ConfigKeyGateTest {

    /** A key as the whole string: dot separated lower case names. */
    private static final Pattern KEY = Pattern.compile("synaxis(\\.[a-z0-9-]+)+");

    /** A key logback substitutes, {@code ${key}} or {@code ${key:-default}}. */
    private static final Pattern SUBSTITUTED = Pattern.compile("\\$\\{(synaxis(\\.[a-z0-9-]+)+)[:}]");

    @Test
    public void theTableOfTheConfigFileIsEveryKeyTheCodeReads() throws IOException {
        final SortedMap<String, SortedSet<String>> read = synaxisStrings();
        final int strings = read.size();
        read.keySet().removeIf(ConfigKeyGateTest::meterName);
        final int meters = strings - read.size();
        // Read as the logging is set up, by logback rather than by a class
        try (InputStream in = Objects.requireNonNull(ConfigFile.class.getResourceAsStream("/logback.xml"),
                "the host's logback.xml")) {
            final Matcher substituted = SUBSTITUTED.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            while (substituted.find())
                read.computeIfAbsent(substituted.group(1), _ -> new TreeSet<>()).add("logback.xml");
        }
        final List<String> problems = new ArrayList<>();
        read.forEach((key, classes) -> {
            if (!ConfigFile.KEYS.contains(key))
                problems.add(key + " is read (" + String.join(", ", classes) + ") but not in ConfigFile.KEYS, "
                        + "so the config file refuses it");
        });
        for (final String key : ConfigFile.KEYS) {
            if (!read.containsKey(key)) problems.add(key + " is in ConfigFile.KEYS, but no class reads it any more");
        }

        System.out.println("config key gate: " + read.size() + " keys read, " + ConfigFile.KEYS.size()
                + " in ConfigFile.KEYS, " + meters + " meter names left out");
        assertTrue(problems.isEmpty(), () -> "Problems of the config key gate (" + problems.size() + "):\n"
                + String.join("\n", problems) + "\n");
    }

    /**
     * Whether the string is the Micrometer name of a metric family of {@code API-PROD-015}: the
     * Prometheus name of a family in {@link HostMetrics#NAMES} is that name with the dots written as
     * underscores, then maybe the unit and the {@code _total} of a counter.
     */
    private static boolean meterName(String name) {
        final String prometheus = name.replace('.', '_');
        return HostMetrics.NAMES.stream().anyMatch(family -> family.equals(prometheus)
                || family.startsWith(prometheus + "_"));
    }

    /**
     * Every string constant of the main classes but {@link ConfigFile} that is a whole
     * {@code synaxis.} name, with the classes holding it.
     */
    private static SortedMap<String, SortedSet<String>> synaxisStrings() {
        final SortedMap<String, SortedSet<String>> found = new TreeMap<>();
        for (final JavaClass javaClass : new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("io.github.bearisbug.synaxis")) {
            if (javaClass.getName().equals(ConfigFile.class.getName())) continue;
            for (final PoolEntry entry : ClassFile.of().parse(bytes(javaClass)).constantPool()) {
                if (entry instanceof StringEntry string && KEY.matcher(string.stringValue()).matches())
                    found.computeIfAbsent(string.stringValue(), _ -> new TreeSet<>()).add(javaClass.getName());
            }
        }
        return found;
    }

    private static byte[] bytes(JavaClass javaClass) {
        try (InputStream in = javaClass.getSource().orElseThrow().getUri().toURL().openStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
