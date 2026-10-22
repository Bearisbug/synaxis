package io.github.bearisbug.synaxis.architecture;

import com.google.gson.JsonParser;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.bearisbug.synaxis.architecture.fixture.ClassNamingAKeyWithoutText;
import io.github.bearisbug.synaxis.architecture.fixture.PluginDeclaringAKeyWithoutText;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The translation key gate ({@code docs/DESIGN-PROD.md} §24, {@code REQ-PROD-041},
 * {@code API-PROD-029}): every translate key the server can send has text in both language files of
 * the client mod, which are all a client ever reads text from; a key without one reaches the player
 * as the bare key.
 * <p>
 * The keys are every string constant of the shape {@code synaxis:<path>} in the main classes on this
 * classpath (the engine's {@code DisconnectReasons}, {@code DisconnectKeys}, the answers and notices
 * of the host, whatever a plugin inlines), minus the identifiers of {@link #NOT_TEXT}, plus the keys
 * each plugin names in {@link SynaxisPlugin#translationKeys()}. It lives here because only this
 * project has the engine, {@code :plugin-api}, the host and the plugins on one classpath.
 */
public class TranslationKeyGateTest {

    /** A key of ours as the whole string: the namespace and a resource path. */
    private static final Pattern KEY = Pattern.compile("synaxis:[a-z0-9_./-]+");

    /**
     * The strings of that shape that are no text. An entry no class names any more fails the gate
     * too, so the list cannot go on hiding a name that a translate key takes later.
     */
    private static final Map<String, String> NOT_TEXT = Map.ofEntries(
            Map.entry("synaxis:handshake", "plugin message channel of the handshake (API-M2-002)"),
            Map.entry("synaxis:main", "plugin message channel the core mode echoes on"),
            Map.entry("synaxis:permission", "Redis channel of role changes (API-PROD-006)"),
            Map.entry("synaxis:moderation", "Redis channel of ban and whitelist changes (API-PROD-006)"),
            Map.entry("synaxis:transfer", "cookie of the transfer ticket (API-PROD-028), also a JSON-RPC method (API-PROD-022)"),
            Map.entry("synaxis:match", "dimension type every match is built on (API-M2-010)"),
            Map.entry("synaxis:map", "dimension type of the map instance of a shard (API-M3-006)"),
            Map.entry("synaxis:play", "plugin message channel of the play layer (API-PLAY-002)"),
            Map.entry("synaxis:shard/drain", "JSON-RPC method of the management interface (API-PROD-022)"),
            Map.entry("synaxis:shard/status", "JSON-RPC method of the management interface (API-PROD-022)"),
            Map.entry("synaxis:broadcast", "JSON-RPC method of the management interface (API-PROD-022)"),
            Map.entry("synaxis:profile", "JSON-RPC method of the management interface (API-PROD-022)"),
            Map.entry("synaxis:player", "id of the host's player data section (API-PROD-032)"));

    /** The language files of the mod, in the directory the build passes as {@code synaxis.lang.dir}. */
    private static final List<String> LANGUAGE_FILES = List.of("en_us.json", "zh_cn.json");

    @Test
    public void everyKeyTheServerCanSendHasTextInBothLanguageFiles() {
        final SortedMap<String, SortedSet<String>> strings = synaxisStrings(new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("io.github.bearisbug.synaxis"));
        final List<String> problems = new ArrayList<>();
        for (final String identifier : new TreeSet<>(NOT_TEXT.keySet())) {
            if (!strings.containsKey(identifier))
                problems.add(identifier + " is listed as no text in NOT_TEXT, but no class names it any more");
        }
        final SortedMap<String, SortedSet<String>> keys = keys(strings, ServiceLoader.load(SynaxisPlugin.class));
        problems.addAll(missing(keys, languageFiles()));

        System.out.println("translation key gate: " + keys.size() + " keys checked against " + LANGUAGE_FILES
                + ", " + NOT_TEXT.size() + " identifiers left out");
        assertTrue(problems.isEmpty(), () -> "Problems of the translation key gate (" + problems.size() + "):\n"
                + String.join("\n", problems) + "\n");
    }

    @Test
    public void theGateReportsAKeyOfAClassAndOfAPluginButNotAChannel() {
        final SortedMap<String, SortedSet<String>> keys = keys(
                synaxisStrings(new ClassFileImporter().importClasses(ClassNamingAKeyWithoutText.class)),
                List.of(new PluginDeclaringAKeyWithoutText()));

        final String fixture = ClassNamingAKeyWithoutText.class.getName();
        assertEquals(List.of(
                "en_us.json has no text for fixture:declared-without-text (from plugin fixture)",
                "en_us.json has no text for synaxis:fixture-without-text (from " + fixture + ")",
                "zh_cn.json has no text for fixture:declared-without-text (from plugin fixture)",
                "zh_cn.json has no text for synaxis:fixture-without-text (from " + fixture + ")"),
                missing(keys, languageFiles()));
    }

    /**
     * Every string constant of these classes that is a whole {@code synaxis:} key or identifier,
     * with the classes holding it. A constant another class inlines shows up in both.
     */
    private static SortedMap<String, SortedSet<String>> synaxisStrings(JavaClasses classes) {
        final SortedMap<String, SortedSet<String>> found = new TreeMap<>();
        for (final JavaClass javaClass : classes) {
            for (final PoolEntry entry : ClassFile.of().parse(bytes(javaClass)).constantPool()) {
                if (entry instanceof StringEntry string && KEY.matcher(string.stringValue()).matches())
                    found.computeIfAbsent(string.stringValue(), _ -> new TreeSet<>()).add(javaClass.getName());
            }
        }
        return found;
    }

    /** The strings without the identifiers of {@link #NOT_TEXT}, plus the keys the plugins name. */
    private static SortedMap<String, SortedSet<String>> keys(SortedMap<String, SortedSet<String>> strings,
                                                             Iterable<? extends SynaxisPlugin> plugins) {
        final SortedMap<String, SortedSet<String>> keys = new TreeMap<>(strings);
        keys.keySet().removeAll(NOT_TEXT.keySet());
        for (final SynaxisPlugin plugin : plugins) {
            for (final String key : plugin.translationKeys())
                keys.computeIfAbsent(key, _ -> new TreeSet<>()).add("plugin " + plugin.descriptor().id());
        }
        return keys;
    }

    /** One line per key a language file has no text for, naming the file, the key and where it is from. */
    private static List<String> missing(SortedMap<String, SortedSet<String>> keys,
                                        SortedMap<String, Set<String>> languageFiles) {
        final List<String> problems = new ArrayList<>();
        languageFiles.forEach((file, texts) -> keys.forEach((key, sources) -> {
            if (!texts.contains(key))
                problems.add(file + " has no text for " + key + " (from " + String.join(", ", sources) + ")");
        }));
        return problems;
    }

    private static SortedMap<String, Set<String>> languageFiles() {
        final Path directory = Path.of(Objects.requireNonNull(System.getProperty("synaxis.lang.dir"),
                "the build passes the mod's language directory as synaxis.lang.dir"));
        final SortedMap<String, Set<String>> files = new TreeMap<>();
        for (final String file : LANGUAGE_FILES) {
            try {
                files.put(file, JsonParser.parseString(Files.readString(directory.resolve(file)))
                        .getAsJsonObject().keySet());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return files;
    }

    private static byte[] bytes(JavaClass javaClass) {
        try (InputStream in = javaClass.getSource().orElseThrow().getUri().toURL().openStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
