package io.github.bearisbug.synaxis.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import io.github.bearisbug.synaxis.instance.Instance;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bake side of the light engine ({@code docs/DESIGN-M3.md} §24): the types of the engine's
 * {@code instance.light} package that {@code :baker} uses run on a {@code WorldView} and must not reference
 * an {@link Instance}. The root module's {@code LightEngineIsolationTest} pins three named types; this takes
 * the set from what the baker actually uses, which only this aggregate project can see. The set is not
 * closed over the package: {@code LightEngine} borrows constants of {@code LightCompute}, whose legacy half
 * reaches {@code Light#getNeighbors} and through it {@code Instance#getChunk}.
 */
public class LightEngineArchitectureTest {
    private static final String LIGHT_PACKAGE = "io.github.bearisbug.synaxis.instance.light";

    private static JavaClasses imported() {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("io.github.bearisbug.synaxis.baker", LIGHT_PACKAGE);
    }

    /** Names of the light package types a baker class depends on. */
    private static Set<String> bakeSide(JavaClasses classes) {
        final Set<String> used = new TreeSet<>();
        for (JavaClass type : classes) {
            if (!type.getPackageName().startsWith("io.github.bearisbug.synaxis.baker")) continue;
            for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
                final JavaClass target = dependency.getTargetClass().getBaseComponentType();
                if (target.getPackageName().equals(LIGHT_PACKAGE)) used.add(target.getName());
            }
        }
        return used;
    }

    private static ArchRule noInstance(Set<String> bakeSide) {
        return noClasses().that(DescribedPredicate.describe("are light engine types the baker uses",
                        (JavaClass type) -> bakeSide.contains(type.getName())))
                .should().dependOnClassesThat().areAssignableTo(Instance.class)
                .because("the baker lights a WorldView, not an instance (REQ-M3-004)");
    }

    @Test
    public void theBakeSideOfTheLightEngineReferencesNoInstance() {
        final JavaClasses classes = imported();
        final Set<String> bakeSide = bakeSide(classes);
        for (String name : Set.of("LightEngine", "LightResult", "WorldView")) {
            assertTrue(bakeSide.contains(LIGHT_PACKAGE + "." + name), bakeSide.toString());
        }
        noInstance(bakeSide).check(classes);
    }
}
