package dev.gameengine;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Layering gate over the engine source, see {@code docs/DESIGN.md} §24.
 */
public class ArchitectureTest {

    /**
     * The lower layer: the packages that carry no engine semantics of their own and must stay usable
     * on their own. Everybody may depend on them, they may only depend on each other.
     * <p>
     * A package belongs here when every one of its classes can be compiled against nothing but the
     * other packages of the list. In practice that means it must not name a registered content type
     * ({@code Block}, {@code Material}, {@code EntityType}, {@code Biome}, …), must not reach the
     * {@code Registries} facade that indexes them, and must not build or read a packet. A package
     * that needs any of those belongs above the line even when it looks like a leaf: {@code color},
     * {@code sound}, {@code crypto}, {@code component} and {@code tag} are out because each of them
     * declares its own {@code NetworkBuffer.Type} wire format, and {@code registry} is out because
     * {@code Registries} names every registered type and {@code DynamicRegistry} produces the
     * configuration phase packets.
     * <p>
     * {@code tools/pkgdeps.py} reports the same number this rule enforces; keep the two lists equal.
     */
    private static final String[] LOWER_LEVEL_PACKAGES = {
            "dev.gameengine.codec..",
            "dev.gameengine.condition..",
            "dev.gameengine.coordinate..",
            "dev.gameengine.exception..",
            "dev.gameengine.gamedata..",
            "dev.gameengine.monitoring..",
            "dev.gameengine.thread..",
            "dev.gameengine.timer..",
            "dev.gameengine.utils..",
    };

    /**
     * {@link ServerFlag} sits in the root package but reads only system properties, so the lower
     * layer may use it. REQ-014 turns it into a configuration object owned by the process; once it
     * does, the classes that read it have to get their values handed to them instead.
     */
    private static final String LOWER_LEVEL_ROOT_CLASS = "dev.gameengine.ServerFlag";

    @Test
    public void lowerLevelPackagesDoNotDependOnTheRestOfTheEngine() {
        JavaClasses engine = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .withImportOption(new ImportOption.DoNotIncludeJars())
                .importPackages("dev.gameengine");

        noClasses()
                .that().resideInAnyPackage(LOWER_LEVEL_PACKAGES)
                .should().dependOnClassesThat(resideInAPackage("dev.gameengine..")
                        .and(not(resideInAnyPackage(LOWER_LEVEL_PACKAGES)))
                        .and(not(name(LOWER_LEVEL_ROOT_CLASS))))
                .because("the lower level packages must not reach back up into the engine")
                .check(engine);
    }
}
