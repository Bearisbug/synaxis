package io.github.bearisbug.synaxis;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import io.github.bearisbug.synaxis.game.rts.GameSideFixture;
import io.github.bearisbug.synaxis.network.ConnectionManager;
import io.github.bearisbug.synaxis.network.plugin.LoginPlugin;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            "io.github.bearisbug.synaxis.codec..",
            "io.github.bearisbug.synaxis.condition..",
            "io.github.bearisbug.synaxis.coordinate..",
            "io.github.bearisbug.synaxis.exception..",
            "io.github.bearisbug.synaxis.gamedata..",
            "io.github.bearisbug.synaxis.monitoring..",
            "io.github.bearisbug.synaxis.thread..",
            "io.github.bearisbug.synaxis.timer..",
            "io.github.bearisbug.synaxis.utils..",
    };

    /**
     * {@link JvmFlags} sits in the root package but reads only system properties and depends on
     * nothing else in the engine, which is the admission test its own javadoc states, so the lower
     * layer may use it. Per process configuration lives in {@code ServerConfig} and is handed to
     * the classes that need it, so it never appears here.
     */
    private static final String LOWER_LEVEL_ROOT_CLASS = "io.github.bearisbug.synaxis.JvmFlags";

    /**
     * The other Gradle modules: the game side ({@code :game-rts}, {@code :protocol}), the stable
     * surface ({@code :plugin-api}) and the host ({@code :server-app}). The engine is the root
     * module and must never name any of them (REQ-M2-002).
     * <p>
     * Every entry is a full package prefix on purpose. The shorthand {@code ..plugin..} matches any
     * package segment named {@code plugin} and would hit the engine's own
     * {@code io.github.bearisbug.synaxis.network.plugin}, so the rule would go red on the engine
     * itself. {@code :protocol} ({@code io.github.bearisbug.synaxis.game.protocol}) needs no entry
     * of its own, the game prefix already covers it.
     * <p>
     * Two blind spots this rule cannot see, and neither can any other bytecode analysis: class
     * names produced by reflection or string concatenation ({@code Class.forName}, four sites in
     * the engine), and the reverse direction "the game side must not reach into engine internals",
     * which no rule living in this module can express because the game classes are not on its
     * classpath. See {@code docs/DESIGN-M2.md} §24 and §28.
     */
    private static final String[] GAME_SIDE_PACKAGES = {
            "io.github.bearisbug.synaxis.game..",
            "io.github.bearisbug.synaxis.plugin..",
            "io.github.bearisbug.synaxis.app..",
    };

    /**
     * Stands in for an engine class that reaches into the game side. Only ever imported as a
     * synthetic {@link JavaClasses}, never scanned as part of the engine.
     */
    private static final class EngineClassReachingIntoTheGameSide implements GameSideFixture {
    }

    /**
     * The second gate behind the Gradle module graph, see {@code docs/DESIGN-M2.md} §24. The first
     * one is Gradle itself: the engine is the root project and the other modules depend on it, so
     * an engine import of a game class needs a main source set cycle that Gradle refuses to build.
     */
    private static ArchRule engineDoesNotDependOnTheGameSide() {
        return noClasses()
                .that().resideInAPackage("io.github.bearisbug.synaxis..")
                .and().resideOutsideOfPackages(GAME_SIDE_PACKAGES)
                .should().dependOnClassesThat().resideInAnyPackage(GAME_SIDE_PACKAGES)
                .because("the engine must not depend on the game side, the stable surface or the host");
    }

    @Test
    public void engineDoesNotDependOnTheGameSideModules() {
        engineDoesNotDependOnTheGameSide().check(engineClasses());
    }

    /**
     * The rule itself is tested against synthetic classes rather than by writing an offending
     * import: an engine class that imports a game class does not compile in the first place, and
     * {@link ImportOption.DoNotIncludeTests} keeps a test written one out of the scanned set.
     */
    @Test
    public void theGameSideRuleRejectsADependencyOnTheGameSide() {
        JavaClasses synthetic = new ClassFileImporter()
                .importClasses(EngineClassReachingIntoTheGameSide.class, GameSideFixture.class);

        AssertionError violation = assertThrows(
                AssertionError.class, () -> engineDoesNotDependOnTheGameSide().check(synthetic));

        // A rule whose that() clause matches nothing fails with an AssertionError of its own
        // (failOnEmptyShould), so only the message proves that the dependency is what went red.
        assertTrue(violation.getMessage().contains(EngineClassReachingIntoTheGameSide.class.getName()),
                violation.getMessage());
        assertTrue(violation.getMessage().contains(GameSideFixture.class.getName()), violation.getMessage());
    }

    /**
     * The counterpart of the rejection above: {@code network.plugin} is the engine's own package and
     * the very reason the forbidden list carries full prefixes.
     */
    @Test
    public void theGameSideRuleAcceptsTheEngineOwnNetworkPluginPackage() {
        JavaClasses synthetic = new ClassFileImporter()
                .importClasses(ConnectionManager.class, LoginPlugin.class);

        engineDoesNotDependOnTheGameSide().check(synthetic);
    }

    @Test
    public void lowerLevelPackagesDoNotDependOnTheRestOfTheEngine() {
        noClasses()
                .that().resideInAnyPackage(LOWER_LEVEL_PACKAGES)
                .should().dependOnClassesThat(resideInAPackage("io.github.bearisbug.synaxis..")
                        .and(not(resideInAnyPackage(LOWER_LEVEL_PACKAGES)))
                        .and(not(name(LOWER_LEVEL_ROOT_CLASS))))
                .because("the lower level packages must not reach back up into the engine")
                .check(engineClasses());
    }

    private static JavaClasses engineClasses() {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .withImportOption(new ImportOption.DoNotIncludeJars())
                .importPackages("io.github.bearisbug.synaxis");
    }
}
