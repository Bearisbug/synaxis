package io.github.bearisbug.synaxis.app.shard;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code docs/DESIGN-M3.md} §24 ArchUnit, the half of the host: the map shard never lights through
 * {@code LightingChunk}, its light comes from the baker. It lives here rather than in
 * {@code :architecture-tests} because the module can say it about its own classes.
 */
public class ShardArchitectureTest {

    private static ArchRule leavesLightingChunkAlone() {
        return noClasses().that().resideInAPackage("io.github.bearisbug.synaxis.app.shard..")
                .should().dependOnClassesThat().haveFullyQualifiedName("io.github.bearisbug.synaxis.instance.LightingChunk")
                .because("MMO instances take their light from the baker, not from LightingChunk (docs/DESIGN-M3.md §5 ①)");
    }

    private static JavaClasses shardClasses() {
        return new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("io.github.bearisbug.synaxis.app.shard");
    }

    @Test
    public void theShardPackageLeavesLightingChunkAlone() {
        leavesLightingChunkAlone().check(shardClasses());
    }

    @Test
    public void theRuleRejectsAClassLightingThroughLightingChunk() {
        final JavaClasses fixture = new ClassFileImporter().importClasses(ShardClassLightingThroughLightingChunk.class);
        final AssertionError refused = assertThrows(AssertionError.class, () -> leavesLightingChunkAlone().check(fixture));
        assertTrue(refused.getMessage().contains("LightingChunk"), refused.getMessage());
    }

    /** The scan must see the package at all, or the rule is green on nothing. */
    @Test
    public void theScanFindsTheShardClasses() {
        assertTrue(shardClasses().contain(ShardWorld.class.getName()));
    }
}
