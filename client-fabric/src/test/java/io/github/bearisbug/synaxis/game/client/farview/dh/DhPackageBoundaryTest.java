package io.github.bearisbug.synaxis.game.client.farview.dh;

import io.github.bearisbug.synaxis.game.client.farview.FarViewClient;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The boundary of {@code docs/DESIGN-M3.md} {@code API-M3-015}: every class of the mod that names a
 * Distant Horizons type is in {@code farview.dh}, and no class outside that package names one of its
 * classes, so a client without DH never loads a DH type (the mod reaches the package only by the name
 * {@code FarViewClient} loads once Fabric reports DH). Read off the constant pools of the compiled
 * main classes.
 */
class DhPackageBoundaryTest {

    private static final String DH_TYPES = "com/seibel/";
    private static final String DH_PACKAGE = "io/github/bearisbug/synaxis/game/client/farview/dh/";

    /** Each main class by its path, with the DH and dh-package names its constant pool holds. */
    private static Map<String, List<String>> references() throws IOException, URISyntaxException {
        final Path classes = Path.of(FarViewClient.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        final Map<String, List<String>> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                final String name = classes.relativize(file).toString().replace('\\', '/');
                final List<String> names = new ArrayList<>();
                for (PoolEntry entry : ClassFile.of().parse(Files.readAllBytes(file)).constantPool()) {
                    if (!(entry instanceof Utf8Entry utf8)) continue;
                    final String text = utf8.stringValue();
                    if (text.contains(DH_TYPES) || text.contains(DH_PACKAGE)) names.add(text);
                }
                found.put(name, names);
            }
        }
        return found;
    }

    @Test
    void onlyTheDhPackageNamesDistantHorizonsOrItself() throws IOException, URISyntaxException {
        final List<String> outside = new ArrayList<>();
        references().forEach((name, names) -> {
            if (!name.startsWith(DH_PACKAGE) && !names.isEmpty()) outside.add(name + " names " + names);
        });
        assertEquals(List.of(), outside);
    }

    @Test
    void theDhPackageDoesNameDistantHorizons() throws IOException, URISyntaxException {
        final Map<String, List<String>> references = references();
        assertFalse(references.isEmpty(), "no main classes found");
        assertFalse(references.entrySet().stream()
                        .filter(entry -> entry.getKey().startsWith(DH_PACKAGE))
                        .noneMatch(entry -> entry.getValue().stream().anyMatch(text -> text.contains(DH_TYPES))),
                "the boundary would hold vacuously: no class of the dh package names a DH type");
    }
}
