package io.github.bearisbug.synaxis.app;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import io.github.bearisbug.synaxis.plugin.InternalLeakFixture;
import io.github.bearisbug.synaxis.plugin.PluginContext;
import org.jetbrains.annotations.ApiStatus;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.base.DescribedPredicate.anyElementThat;
import static com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.annotatedWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noCodeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate over the stable surface, see {@code docs/DESIGN-M2.md} §24.
 * <p>
 * "The game side must not reach into engine internals" cannot be expressed by a rule living in the
 * engine, and a rule living in a plugin only ever covers that one plugin. This is the half that
 * needs neither: whatever {@code :plugin-api} hands a plugin is what the plugin is entitled to
 * touch, so an {@link ApiStatus.Internal} type appearing in a signature of the surface makes
 * reaching into internals the documented way to use it. Keeping it out leaves every remaining
 * violation a deliberate act on the plugin author's side.
 * <p>
 * The rule is about types, not members: {@code PluginContext#process()} is the signed off escape
 * hatch (S4 in §28.1) and {@link io.github.bearisbug.synaxis.ServerProcess} carries no annotation,
 * so the internal members reachable through it stay out of scope.
 */
public class StableSurfaceArchitectureTest {

    private static final String STABLE_SURFACE = "io.github.bearisbug.synaxis.plugin..";
    private static final DescribedPredicate<JavaClass> INTERNAL = annotatedWith(ApiStatus.Internal.class).forSubtype();

    private static ArchRule noInternalTypeInAReturnType() {
        return noMethods()
                .that().areDeclaredInClassesThat().resideInAPackage(STABLE_SURFACE)
                .and().areDeclaredInClassesThat().arePublic()
                .and().arePublic()
                .should().haveRawReturnType(INTERNAL)
                .because("a plugin must not have to name an engine internal to read the stable surface");
    }

    private static ArchRule noInternalTypeInAParameter() {
        return noCodeUnits()
                .that().areDeclaredInClassesThat().resideInAPackage(STABLE_SURFACE)
                .and().areDeclaredInClassesThat().arePublic()
                .and().arePublic()
                .should().haveRawParameterTypes(anyElementThat(INTERNAL))
                .because("a plugin must not have to build an engine internal to call the stable surface");
    }

    @Test
    public void theStableSurfaceReturnsNoEngineInternal() {
        noInternalTypeInAReturnType().check(stableSurface());
    }

    @Test
    public void theStableSurfaceAsksForNoEngineInternal() {
        noInternalTypeInAParameter().check(stableSurface());
    }

    @Test
    public void theReturnTypeRuleRejectsALeakedInternal() {
        AssertionError violation = assertThrows(AssertionError.class,
                () -> noInternalTypeInAReturnType().check(fixture()));

        assertTrue(violation.getMessage().contains("leakedByReturnType"), violation.getMessage());
    }

    @Test
    public void theParameterRuleRejectsALeakedInternal() {
        AssertionError violation = assertThrows(AssertionError.class,
                () -> noInternalTypeInAParameter().check(fixture()));

        assertTrue(violation.getMessage().contains("leakedByParameter"), violation.getMessage());
    }

    /**
     * {@code :plugin-api} arrives as a jar, so jars must stay in; the fixture lives in the same
     * package as the surface and is kept out by {@link ImportOption.DoNotIncludeTests}.
     */
    private static JavaClasses stableSurface() {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages(PluginContext.class.getPackageName());
    }

    private static JavaClasses fixture() {
        return new ClassFileImporter().importClasses(InternalLeakFixture.class);
    }
}
