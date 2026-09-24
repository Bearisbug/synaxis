package io.github.bearisbug.synaxis.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import io.github.bearisbug.synaxis.baker.fixture.BakerClassReadingAPaletteInternal;
import org.jetbrains.annotations.ApiStatus;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The module boundary of {@code :baker} ({@code docs/DESIGN-M3.md} §6 and §24). The baker's package
 * sits inside the engine's tree, so the game side rule of {@link EngineInternalsArchitectureTest} does
 * not stop it from using engine internals; what stops it is the §6 table, the complete list of the
 * internals {@code :synx} and {@code :baker} may use. The same table is written out below, and a change
 * to one goes with a change to the other.
 */
public class BakerArchitectureTest {

    private static final String BAKER_PACKAGE = "io.github.bearisbug.synaxis.baker..";

    /** The internal types of the §6 table: every member of these may be used. */
    private static final Set<String> ALLOWED_INTERNAL_TYPES = Set.of(
            "io.github.bearisbug.synaxis.network.packet.server.BufferedPacket",
            "io.github.bearisbug.synaxis.network.packet.server.CachedPacket",
            "io.github.bearisbug.synaxis.instance.light.LightBlocks",
            // GeneratorSource runs an engine Generator on one chunk the way an instance does
            "io.github.bearisbug.synaxis.instance.generator.GeneratorImpl");

    /** The internal members of the §6 table on types that are otherwise public. */
    private static final Set<String> ALLOWED_INTERNAL_MEMBERS = Set.of(
            "io.github.bearisbug.synaxis.instance.Chunk#getFullDataPacket");

    /**
     * The engine as {@link EngineInternalsArchitectureTest} defines it, minus the two M3 engine side
     * modules: their own internal types are theirs to use.
     */
    private static boolean isEngine(JavaClass owner) {
        final String pkg = owner.getPackageName();
        return inTree(pkg, "io.github.bearisbug.synaxis")
                && !inTree(pkg, "io.github.bearisbug.synaxis.game")
                && !inTree(pkg, "io.github.bearisbug.synaxis.plugin")
                && !inTree(pkg, "io.github.bearisbug.synaxis.app")
                && !inTree(pkg, "io.github.bearisbug.synaxis.baker")
                && !inTree(pkg, "io.github.bearisbug.synaxis.synx");
    }

    private static boolean inTree(String pkg, String root) {
        return pkg.equals(root) || pkg.startsWith(root + ".");
    }

    private static final DescribedPredicate<JavaClass> UNLISTED_INTERNAL_TYPE =
            DescribedPredicate.describe("an engine type marked @ApiStatus.Internal outside of the §6 table",
                    owner -> isEngine(owner) && owner.isAnnotatedWith(ApiStatus.Internal.class)
                            && !ALLOWED_INTERNAL_TYPES.contains(owner.getName()));

    private static final DescribedPredicate<JavaAccess<?>> UNLISTED_INTERNAL_MEMBER =
            DescribedPredicate.describe("an engine member marked @ApiStatus.Internal outside of the §6 table", access ->
                    access.getTarget().resolveMember()
                            .map(member -> isEngine(member.getOwner())
                                    && member.isAnnotatedWith(ApiStatus.Internal.class)
                                    && !ALLOWED_INTERNAL_TYPES.contains(member.getOwner().getName())
                                    && !ALLOWED_INTERNAL_MEMBERS.contains(member.getOwner().getName() + "#" + member.getName()))
                            .orElse(false));

    private static ArchRule bakerNamesNoUnlistedInternalType() {
        return noClasses().that().resideInAPackage(BAKER_PACKAGE)
                .should().dependOnClassesThat(UNLISTED_INTERNAL_TYPE)
                .because("docs/DESIGN-M3.md §6 lists every engine internal :baker may use");
    }

    private static ArchRule bakerTouchesNoUnlistedInternalMember() {
        return noClasses().that().resideInAPackage(BAKER_PACKAGE)
                .should().accessTargetWhere(UNLISTED_INTERNAL_MEMBER)
                .because("docs/DESIGN-M3.md §6 lists every engine internal :baker may use");
    }

    @Test
    public void theBakerUsesOnlyTheListedEngineInternals() {
        bakerNamesNoUnlistedInternalType().check(bakerClasses());
        bakerTouchesNoUnlistedInternalMember().check(bakerClasses());
    }

    @Test
    public void theMemberRuleRejectsABakerClassReadingAnUnlistedInternal() {
        AssertionError violation = assertThrows(AssertionError.class, () -> bakerTouchesNoUnlistedInternalMember()
                .check(new ClassFileImporter().importClasses(BakerClassReadingAPaletteInternal.class)));

        assertTrue(violation.getMessage().contains("singleValue"), violation.getMessage());
    }

    /**
     * The MMO path computes light through the light engine, never through {@code LightingChunk} (§24).
     */
    @Test
    public void theBakerDoesNotUseLightingChunk() {
        noClasses().that().resideInAPackage(BAKER_PACKAGE)
                .should().dependOnClassesThat().haveFullyQualifiedName("io.github.bearisbug.synaxis.instance.LightingChunk")
                .check(bakerClasses());
    }

    /**
     * The Gradle graph already forbids it (the baker depends on the engine); this names the direction.
     */
    @Test
    public void theEngineDoesNotDependOnTheBaker() {
        JavaClasses engine = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("io.github.bearisbug.synaxis");
        noClasses().that(DescribedPredicate.describe("are engine classes", BakerArchitectureTest::isEngine))
                .should().dependOnClassesThat().resideInAPackage(BAKER_PACKAGE)
                .check(engine);
    }

    private static JavaClasses bakerClasses() {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("io.github.bearisbug.synaxis.baker");
    }
}
