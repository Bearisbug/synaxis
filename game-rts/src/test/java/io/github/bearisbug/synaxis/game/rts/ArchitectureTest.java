package io.github.bearisbug.synaxis.game.rts;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodReference;
import com.tngtech.archunit.core.domain.properties.HasName;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import io.github.bearisbug.synaxis.exception.ExceptionManager;
import io.github.bearisbug.synaxis.tag.Tag;
import io.github.bearisbug.synaxis.tag.TagHandler;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.function.Function;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two gates the game side owes: the ban on the structured tags of {@code docs/DESIGN-M2.md} §24
 * rule ③ (REQ-M2-015), and the third copy of the log bound of {@code docs/DESIGN.md} §15
 * (REQ-M2-009).
 * <p>
 * Both live here rather than in the engine's own {@code ArchitectureTest} because that one imports
 * the classes on the engine's classpath, where no game class can ever be: written there the rules
 * would be green forever. A rule only ever watches its own module's classpath, which is why the
 * engine, the host and now the game side each keep their own copy of the log rule
 * ({@code docs/ENGINE.md} §8).
 */
public class ArchitectureTest {

    private static final String GAME_SIDE_PACKAGE = "io.github.bearisbug.synaxis.game..";

    /**
     * {@link Tag#Structure} and {@link Tag#View} read their sub compound through
     * {@link TagHandler#fromCompound}, which registers every key it meets in a process wide index
     * that never shrinks (audit F49).
     * <p>
     * The ban is unconditional, not "not on client supplied compounds": where a compound came from
     * is a runtime taint, and bytecode analysis cannot see it, so a rule phrased that way would
     * enforce nothing. The game side pays for it by keeping its gameplay data out of the tag system,
     * which costs it nothing: the gameplay data model is its own and travels over custom payloads.
     * The only tags it names are the client tags a data marker handler declares
     * ({@code marker.LootMarker}), plain tags read by key, never through {@link TagHandler#fromCompound}.
     * <p>
     * Blind spot, shared with every bytecode rule: a class name produced by reflection or by string
     * concatenation ({@code Class.forName("…TagHandler")}) is invisible here. So is the reverse
     * direction "the game side must not reach into engine internals", which needs an aggregating
     * module that holds every module as {@code testImplementation}. See {@code docs/DESIGN-M2.md}
     * §24 and §28.
     */
    private static final DescribedPredicate<JavaAccess<?>> STRUCTURED_TAG_ENTRY_POINTS =
            DescribedPredicate.describe("Tag.Structure, Tag.View or TagHandler.fromCompound", access -> {
                final String owner = access.getTargetOwner().getFullName();
                final String name = access.getTarget().getName();
                if (owner.equals(Tag.class.getName())) return name.equals("Structure") || name.equals("View");
                return owner.equals(TagHandler.class.getName()) && name.equals("fromCompound");
            });

    /**
     * Phrased over {@link JavaAccess} rather than {@code callMethodWhere}: ArchUnit models a method
     * reference as a {@link JavaMethodReference}, which is not a method call and never appears in
     * {@code getMethodCallsFromSelf()}. A {@code callMethodWhere} rule passes
     * {@code TagHandler::fromCompound} straight through, and this one is meant to be a gate with no
     * way around it.
     */
    private static ArchRule gameSideDoesNotReadCompoundsIntoTags() {
        return noClasses()
                .that().resideInAPackage(GAME_SIDE_PACKAGE)
                .should().accessTargetWhere(STRUCTURED_TAG_ENTRY_POINTS)
                .because("the game side must not run a compound through the tag index (audit F49)");
    }

    /**
     * Stands in for a game class doing what the rule forbids. Only ever imported as a synthetic
     * {@link JavaClasses}: {@link ImportOption.DoNotIncludeTests} keeps it out of the scanned set.
     */
    private static final class GameClassCallingTheTagIndex {
        @SuppressWarnings("unused")
        static TagHandler read(CompoundBinaryTag compound) {
            return TagHandler.fromCompound(compound);
        }
    }

    /**
     * The same violation written as a method reference. It is what keeps the rule from being
     * narrowed back to {@code callMethodWhere}: under that phrasing this class is not a violation
     * and {@link #theRuleRejectsAGameClassReferencingTheTagIndex()} goes red.
     */
    private static final class GameClassReferencingTheTagIndex {
        @SuppressWarnings("unused")
        static Function<CompoundBinaryTag, TagHandler> reader() {
            return TagHandler::fromCompound;
        }
    }

    @Test
    public void gameSideDoesNotUseTheStructuredTags() {
        gameSideDoesNotReadCompoundsIntoTags().check(gameSideClasses());
    }

    @Test
    public void theRuleRejectsAGameClassCallingTheTagIndex() {
        assertTheRuleRejects(GameClassCallingTheTagIndex.class);
    }

    @Test
    public void theRuleRejectsAGameClassReferencingTheTagIndex() {
        assertTheRuleRejects(GameClassReferencingTheTagIndex.class);
    }

    private static void assertTheRuleRejects(Class<?> offender) {
        JavaClasses synthetic = new ClassFileImporter().importClasses(offender);

        AssertionError violation = assertThrows(
                AssertionError.class, () -> gameSideDoesNotReadCompoundsIntoTags().check(synthetic));

        assertTrue(violation.getMessage().contains(offender.getName()), violation.getMessage());
        assertTrue(violation.getMessage().contains("fromCompound"), violation.getMessage());
    }

    /**
     * Same targets as the engine and host rules: a WARN or ERROR line, a stack trace on the
     * standard error stream, or an exception handed to {@link ExceptionManager}, whose default sink
     * is {@code Throwable::printStackTrace}. It matches by method name on {@link Logger}, so the
     * fluent {@code LOGGER.atWarn().log(…)} goes through unseen — the same gap the other two have,
     * registered in {@code docs/ENGINE.md} §8.
     */
    private static DescribedPredicate<JavaCall<?>> writesALogLine() {
        return target(owner(assignableTo(Logger.class))).and(target(name("warn").or(name("error"))))
                .or(target(owner(assignableTo(Throwable.class))).and(target(name("printStackTrace"))))
                .or(target(owner(assignableTo(ExceptionManager.class))).and(target(name("handleException"))));
    }

    /**
     * The one game class that may still write a line itself: {@link Match} reports a map it could
     * not generate, which happens at most once per match and before the port is even bound. Nothing
     * a client sends can reach it.
     * <p>
     * {@link GameChannel} is deliberately absent although it is the class with the most reasons to
     * write a line: every payload it sees is a client's to send, so it reports through a
     * {@code LogBudget} instead and a plain line added next to a budgeted one turns this rule red.
     * That is the point of leaving it out — an exemption by class would have let the unbounded line
     * in next to the bounded one, which is blind spot ① of the engine rule.
     */
    private static DescribedPredicate<HasName> boundedReporters() {
        return name(Match.class.getName());
    }

    private static ArchRule gameSidePathsDoNotLogPerPacket() {
        return noClasses()
                .that().resideInAPackage(GAME_SIDE_PACKAGE)
                .and(not(boundedReporters()))
                .should().callCodeUnitWhere(writesALogLine())
                .because("a client decides how often these run, so the size of the log must not follow it");
    }

    @Test
    public void gameSidePathsDoNotWriteALinePerPacket() {
        gameSidePathsDoNotLogPerPacket().check(gameSideClasses());
    }

    /**
     * The rule is only worth having if it fires, and a main source violation would break the build
     * it is meant to guard, so the offender is a test only class in the same package.
     */
    @Test
    public void theGameSideLogRuleRejectsALinePerPayload() {
        JavaClasses synthetic = new ClassFileImporter().importClasses(UnboundedGameLogFixture.class);

        AssertionError violation = assertThrows(
                AssertionError.class, () -> gameSidePathsDoNotLogPerPacket().check(synthetic));

        assertTrue(violation.getMessage().contains(UnboundedGameLogFixture.class.getName()),
                violation.getMessage());
    }

    /**
     * Both this import and {@link #GAME_SIDE_PACKAGE} stop at the {@code game} package tree, and
     * jars are left out, which is what keeps {@code :protocol} — same package prefix, different
     * module, its own classpath — from being scanned by the module that merely uses it.
     */
    private static JavaClasses gameSideClasses() {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .withImportOption(new ImportOption.DoNotIncludeJars())
                .importPackages("io.github.bearisbug.synaxis.game");
    }
}
