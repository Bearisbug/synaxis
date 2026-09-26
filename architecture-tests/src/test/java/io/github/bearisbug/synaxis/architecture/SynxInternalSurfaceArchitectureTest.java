package io.github.bearisbug.synaxis.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import io.github.bearisbug.synaxis.synx.fixture.SynxClassFramingThroughTheEngine;
import org.jetbrains.annotations.ApiStatus;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code docs/DESIGN-M3.md} §24 「引擎内部面」: {@code :synx} is an engine side module and may use
 * engine internals, but only the ones the §6 internal surface table lists. The table is written out
 * here as constants; a change to either is a change to both.
 * <p>
 * The same aggregate reason as {@link EngineInternalsArchitectureTest}: the rule needs the engine's
 * annotations and the {@code :synx} classes on one classpath. {@code :baker} joins the scanned
 * packages when it exists.
 */
public class SynxInternalSurfaceArchitectureTest {

    private static final String SYNX_PACKAGE = "io.github.bearisbug.synaxis.synx..";

    /** Internal engine types of the §6 table: {@code :synx} may name them and use any of their members. */
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "io.github.bearisbug.synaxis.network.packet.server.BufferedPacket",
            "io.github.bearisbug.synaxis.network.packet.server.CachedPacket",
            "io.github.bearisbug.synaxis.instance.light.LightBlocks");

    /** Internal members of public engine types in the §6 table, as {@code owner#name}. */
    private static final Set<String> ALLOWED_MEMBERS = Set.of(
            "io.github.bearisbug.synaxis.instance.Chunk#getFullDataPacket",
            "io.github.bearisbug.synaxis.instance.block.BlockManager#getHandlerOrDummy");

    private static boolean isEngineOwned(JavaClass owner) {
        final String pkg = owner.getPackageName();
        return inTree(pkg, "io.github.bearisbug.synaxis")
                && !inTree(pkg, "io.github.bearisbug.synaxis.game")
                && !inTree(pkg, "io.github.bearisbug.synaxis.plugin")
                && !inTree(pkg, "io.github.bearisbug.synaxis.app")
                && !inTree(pkg, "io.github.bearisbug.synaxis.synx")
                && !inTree(pkg, "io.github.bearisbug.synaxis.baker");
    }

    private static boolean inTree(String pkg, String root) {
        return pkg.equals(root) || pkg.startsWith(root + ".");
    }

    private static final DescribedPredicate<JavaClass> UNLISTED_INTERNAL_TYPE =
            DescribedPredicate.describe("an engine type marked @ApiStatus.Internal outside the §6 table", owner ->
                    isEngineOwned(owner) && owner.isAnnotatedWith(ApiStatus.Internal.class)
                            && !ALLOWED_TYPES.contains(owner.getName()));

    private static final DescribedPredicate<JavaAccess<?>> UNLISTED_INTERNAL_MEMBER =
            DescribedPredicate.describe("an engine member marked @ApiStatus.Internal outside the §6 table", access ->
                    access.getTarget().resolveMember()
                            .map(member -> isEngineOwned(member.getOwner())
                                    && member.isAnnotatedWith(ApiStatus.Internal.class)
                                    && !ALLOWED_TYPES.contains(member.getOwner().getName())
                                    && !ALLOWED_MEMBERS.contains(member.getOwner().getName() + "#" + member.getName()))
                            .orElse(false));

    private static ArchRule namesOnlyListedInternalTypes() {
        return noClasses().that().resideInAPackage(SYNX_PACKAGE)
                .should().dependOnClassesThat(UNLISTED_INTERNAL_TYPE)
                .because("the §6 internal surface table of docs/DESIGN-M3.md is the whole of what :synx may use");
    }

    private static ArchRule touchesOnlyListedInternalMembers() {
        return noClasses().that().resideInAPackage(SYNX_PACKAGE)
                .should().accessTargetWhere(UNLISTED_INTERNAL_MEMBER)
                .because("the §6 internal surface table of docs/DESIGN-M3.md is the whole of what :synx may use");
    }

    /** §24 ArchUnit: the MMO path never lights through {@code LightingChunk}. */
    private static ArchRule leavesLightingChunkAlone() {
        return noClasses().that().resideInAPackage(SYNX_PACKAGE)
                .should().dependOnClassesThat().haveFullyQualifiedName("io.github.bearisbug.synaxis.instance.LightingChunk")
                .because("MMO instances take their light from the baker, not from LightingChunk (docs/DESIGN-M3.md §5 ①)");
    }

    @Test
    public void synxNamesOnlyListedInternalTypes() {
        namesOnlyListedInternalTypes().check(synxClasses());
    }

    @Test
    public void synxTouchesOnlyListedInternalMembers() {
        touchesOnlyListedInternalMembers().check(synxClasses());
    }

    @Test
    public void synxLeavesLightingChunkAlone() {
        leavesLightingChunkAlone().check(synxClasses());
    }

    @Test
    public void rulesRejectUnlistedInternalsAndLightingChunk() {
        final JavaClasses fixture = new ClassFileImporter().importClasses(SynxClassFramingThroughTheEngine.class);
        final AssertionError type = assertThrows(AssertionError.class, () -> namesOnlyListedInternalTypes().check(fixture));
        assertTrue(type.getMessage().contains("PacketWriting"), type.getMessage());
        final AssertionError member = assertThrows(AssertionError.class, () -> touchesOnlyListedInternalMembers().check(fixture));
        assertTrue(member.getMessage().contains("singleValue"), member.getMessage());
        final AssertionError lighting = assertThrows(AssertionError.class, () -> leavesLightingChunkAlone().check(fixture));
        assertTrue(lighting.getMessage().contains("LightingChunk"), lighting.getMessage());
    }

    /** The scan must see the module at all, or every rule above is green on nothing. */
    @Test
    public void scanFindsTheSynxClasses() {
        assertTrue(synxClasses().contain("io.github.bearisbug.synaxis.synx.SynxRegion"));
    }

    private static JavaClasses synxClasses() {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("io.github.bearisbug.synaxis.synx");
    }
}
