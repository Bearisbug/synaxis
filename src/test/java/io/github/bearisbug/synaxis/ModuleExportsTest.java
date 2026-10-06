package io.github.bearisbug.synaxis;

import org.junit.jupiter.api.Test;

import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every package of the engine is part of its API: {@code module-info.java} exports all of them, so a server or a plugin
 * on the module path can use any public type (upstream Minestom #3373 found {@code item.predicate} left out).
 */
class ModuleExportsTest {

    @Test
    void everyPackageIsExported() throws URISyntaxException {
        final Path classes = Path.of(MinecraftServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        final ModuleReference module = ModuleFinder.of(classes).findAll().iterator().next();
        final ModuleDescriptor descriptor = module.descriptor();

        final Set<String> unexported = new TreeSet<>(descriptor.packages());
        for (ModuleDescriptor.Exports exports : descriptor.exports()) {
            if (!exports.isQualified()) unexported.remove(exports.source());
        }
        assertEquals(Set.of(), unexported, () -> "packages of " + descriptor.name() + " that module-info does not export");
    }
}
