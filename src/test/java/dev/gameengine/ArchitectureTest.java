package dev.gameengine;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Layering gate over the engine source, see {@code docs/DESIGN.md} §24.
 */
public class ArchitectureTest {

    /**
     * The packages that carry no engine semantics of their own and must stay usable on their own:
     * everybody may depend on them, they may only depend on each other.
     */
    private static final String[] LOWER_LEVEL_PACKAGES = {
            "dev.gameengine.codec..",
            "dev.gameengine.color..",
            "dev.gameengine.component..",
            "dev.gameengine.condition..",
            "dev.gameengine.coordinate..",
            "dev.gameengine.crypto..",
            "dev.gameengine.exception..",
            "dev.gameengine.gamedata..",
            "dev.gameengine.monitoring..",
            "dev.gameengine.registry..",
            "dev.gameengine.sound..",
            "dev.gameengine.tag..",
            "dev.gameengine.thread..",
            "dev.gameengine.timer..",
            "dev.gameengine.utils..",
    };

    /**
     * The rule is frozen rather than enforced outright: the 39 reverse edges inherited from upstream
     * are recorded in {@code src/test/resources/archunit_store} and stay accepted, so the gate only
     * fails on edges added after the freeze. REQ-015 removes the recorded ones and unfreezes the rule.
     */
    @Test
    public void lowerLevelPackagesDoNotDependOnTheRestOfTheEngine() {
        JavaClasses engine = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .withImportOption(new ImportOption.DoNotIncludeJars())
                .importPackages("dev.gameengine");

        ArchRule rule = noClasses()
                .that().resideInAnyPackage(LOWER_LEVEL_PACKAGES)
                .should().dependOnClassesThat(resideInAPackage("dev.gameengine..")
                        .and(not(resideInAnyPackage(LOWER_LEVEL_PACKAGES))))
                .because("the lower level packages must not reach back up into the engine");

        FreezingArchRule.freeze(rule).check(engine);
    }
}
