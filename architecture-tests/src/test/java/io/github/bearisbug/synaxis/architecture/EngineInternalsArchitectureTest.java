package io.github.bearisbug.synaxis.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.game.fixture.GameClassDeclaringAnEngineInternal;
import io.github.bearisbug.synaxis.game.fixture.GameClassMovingEntitiesTheInternalWay;
import org.jetbrains.annotations.ApiStatus;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reverse direction of {@code docs/DESIGN-M2.md} §24: the game side must not reach into engine
 * internals. It is the half that needs this aggregate project, and the reason the project exists.
 * <p>
 * A rule only ever sees its own module's classpath. Written in the engine it would be green
 * forever, because no game class can be on the engine's classpath without a module cycle; written
 * in {@code :game-rts} it would cover that one plugin. Here both sides are on one classpath, so the
 * rule reads the annotations the engine actually carries instead of a hand kept list.
 * <p>
 * Two blind spots. One is shared with every bytecode rule: a class named through reflection or
 * string concatenation is invisible, and the engine has four {@code Class.forName} sites of its
 * own. The other is this pair's alone: a game class that overrides an internal engine method and
 * never calls {@code super} makes no access and names no internal type, so both rules pass it
 * ({@code updateNewViewer(Player)} takes a public parameter). The compiler covers the part that
 * matters most — {@code MissingOverride} is an error in this build, so {@code @Override} fails
 * the build the day the engine renames or removes the hook. What nothing catches is the engine
 * changing when it calls it.
 */
public class EngineInternalsArchitectureTest {

    private static final String GAME_SIDE_PACKAGE = "io.github.bearisbug.synaxis.game..";

    /**
     * What the engine keeps to itself: everything under the root package except the three trees
     * that are not the engine — the game side, the stable surface and the host.
     * <p>
     * {@code :plugin-api} is excluded on purpose rather than by oversight. {@code LogBudget} lives
     * there and carries {@link ApiStatus.Internal} so that it stays out of the ABI gate, yet
     * {@code :game-rts} is meant to use it: §24 puts the two of them on the same client driven path
     * under one bound. Matching is by package segment, which is what keeps the engine's own
     * {@code network.plugin} (login plugin messages, nothing to do with plugin hosting) inside the
     * engine where it belongs.
     */
    private static boolean isEngineOwned(JavaClass owner) {
        final String pkg = owner.getPackageName();
        return inTree(pkg, "io.github.bearisbug.synaxis")
                && !inTree(pkg, "io.github.bearisbug.synaxis.game")
                && !inTree(pkg, "io.github.bearisbug.synaxis.plugin")
                && !inTree(pkg, "io.github.bearisbug.synaxis.app");
    }

    private static boolean inTree(String pkg, String root) {
        return pkg.equals(root) || pkg.startsWith(root + ".");
    }

    private static final DescribedPredicate<JavaClass> ENGINE_INTERNAL_TYPE =
            DescribedPredicate.describe("an engine type marked @ApiStatus.Internal",
                    owner -> isEngineOwned(owner) && owner.isAnnotatedWith(ApiStatus.Internal.class));

    /**
     * Phrased over {@link JavaAccess} and not {@code callMethodWhere}, for the reason §24 spells
     * out: ArchUnit models a method reference as its own kind of access, and a {@code callMethod}
     * rule lets {@code Entity::refreshPosition} through untouched.
     * <p>
     * The member is resolved rather than matched by name, and the owner is read off the resolved
     * member rather than off the call site: a game class calling {@code refreshPosition} on its own
     * {@code Entity} subclass compiles to a call whose target owner is that subclass, and a rule
     * that judged the owner at the call site would let every inherited internal through. The
     * fixture calls through a subclass for exactly that reason.
     */
    private static final DescribedPredicate<JavaAccess<?>> ENGINE_INTERNAL_MEMBER =
            DescribedPredicate.describe("an engine member marked @ApiStatus.Internal", access ->
                    access.getTarget().resolveMember()
                            .map(member -> isEngineOwned(member.getOwner())
                                    && member.isAnnotatedWith(ApiStatus.Internal.class))
                            .orElse(false));

    private static ArchRule gameSideNamesNoInternalType() {
        return noClasses()
                .that().resideInAPackage(GAME_SIDE_PACKAGE)
                .should().dependOnClassesThat(ENGINE_INTERNAL_TYPE)
                .because("a type the engine marked internal carries no promise the game side may build on");
    }

    private static ArchRule gameSideTouchesNoInternalMember() {
        return noClasses()
                .that().resideInAPackage(GAME_SIDE_PACKAGE)
                .should().accessTargetWhere(ENGINE_INTERNAL_MEMBER)
                .because("a member the engine marked internal may change in any release, plugins and all");
    }

    @Test
    public void theGameSideNamesNoEngineInternalType() {
        gameSideNamesNoInternalType().check(gameSideClasses());
    }

    @Test
    public void theGameSideTouchesNoEngineInternalMember() {
        gameSideTouchesNoInternalMember().check(gameSideClasses());
    }

    @Test
    public void theTypeRuleRejectsAGameClassDeclaringAnInternal() {
        AssertionError violation = assertThrows(AssertionError.class,
                () -> gameSideNamesNoInternalType().check(fixture(GameClassDeclaringAnEngineInternal.class)));

        assertTrue(violation.getMessage().contains(GameClassDeclaringAnEngineInternal.class.getName()),
                violation.getMessage());
    }

    /**
     * The fixture advances an entity through {@code refreshPosition} instead of {@link
     * Entity#moveTo(Pos)}, which is the violation REQ-M2-025 was signed off to make impossible
     * (S4 in §28.1). Until this project existed, "the game side calls no {@code refreshPosition}"
     * was a grep in a test case; here it is a consequence of the annotation the engine already
     * carries, and it covers every other internal entry point along with it.
     */
    @Test
    public void theMemberRuleRejectsAGameClassCallingAnInternal() {
        AssertionError violation = assertThrows(AssertionError.class,
                () -> gameSideTouchesNoInternalMember().check(fixture(GameClassMovingEntitiesTheInternalWay.class)));

        assertTrue(violation.getMessage().contains("refreshPosition"), violation.getMessage());
    }

    /**
     * The engine entry point {@code plugin-api} re-exports without the ABI gate covering it
     * (REQ-M2-025, §24). The gate applies to {@code :plugin-api} and {@code :protocol}; this method
     * lives in the engine root module, so nothing mechanical noticed a change to it before. Adding
     * a second such entry point means adding it here, in the same change that signs it off.
     * <p>
     * Read from the class file, not through reflection: {@link ApiStatus.Internal} is retained at
     * {@code CLASS} level only, so {@code Method#isAnnotationPresent} answers false whether or not
     * the annotation is there.
     */
    @Test
    public void theEnginePositionEntryPointStillHasTheSignaturePluginsWereGiven() {
        JavaClass entity = new ClassFileImporter().importClasses(Entity.class).get(Entity.class);
        Optional<JavaMethod> found = entity.tryGetMethod("moveTo", Pos.class);
        assertTrue(found.isPresent(),
                "Entity#moveTo(Pos) is part of the stable surface: removing or renaming it is a"
                        + " breaking change of plugin-api and goes through §8 evolution and deprecation");
        JavaMethod moveTo = found.get();

        assertTrue(moveTo.getModifiers().contains(JavaModifier.PUBLIC), "moveTo stopped being public");
        assertTrue(moveTo.getRawReturnType().isEquivalentTo(void.class), "moveTo changed what it returns");
        assertFalse(moveTo.isAnnotatedWith(ApiStatus.Internal.class),
                "moveTo was marked internal, which takes back the entry point plugins were given"
                        + " instead of deprecating it");
    }

    /**
     * Jars stay in, unlike the module local rules: project dependencies reach this test through
     * the jars of the modules they come from, and leaving them out would scan nothing at all. The
     * fixtures share the game package prefix and are kept out by
     * {@link ImportOption.DoNotIncludeTests}.
     */
    private static JavaClasses gameSideClasses() {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("io.github.bearisbug.synaxis.game");
    }

    private static JavaClasses fixture(Class<?> offender) {
        return new ClassFileImporter().importClasses(offender);
    }
}
