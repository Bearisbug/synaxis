package io.github.bearisbug.synaxis.game.rush;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClasses;
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

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two rules every game module carries in its own test source set ({@code docs/DESIGN-M2.md} §24
 * rules ③ and ⑤, {@code docs/DESIGN-PLAY.md} §6.8 step 2), copied from {@code :game-rts}: no structured
 * tags, and no log line a client decides the number of.
 * <p>
 * A rule only sees its own module's classpath, so the copy in {@code :game-rts} cannot see this module.
 * Jars are left out of the import, which keeps {@code :play-kit} and {@code :protocol} out: same package
 * prefix, other modules. {@code Round} of the play kit writes one ERROR line per failed callback of a
 * round, after which the round is closed; nothing a client sends reaches it.
 */
public class ArchitectureTest {

    private static final String GAME_SIDE_PACKAGE = "io.github.bearisbug.synaxis.game..";

    /** {@link Tag#Structure}, {@link Tag#View} and {@link TagHandler#fromCompound}: the tag index that never shrinks (audit F49). */
    private static final DescribedPredicate<JavaAccess<?>> STRUCTURED_TAG_ENTRY_POINTS =
            DescribedPredicate.describe("Tag.Structure, Tag.View or TagHandler.fromCompound", access -> {
                final String owner = access.getTargetOwner().getFullName();
                final String name = access.getTarget().getName();
                if (owner.equals(Tag.class.getName())) return name.equals("Structure") || name.equals("View");
                return owner.equals(TagHandler.class.getName()) && name.equals("fromCompound");
            });

    /** Over {@link JavaAccess}, so that a method reference is caught as well as a call. */
    private static ArchRule gameSideDoesNotReadCompoundsIntoTags() {
        return noClasses()
                .that().resideInAPackage(GAME_SIDE_PACKAGE)
                .should().accessTargetWhere(STRUCTURED_TAG_ENTRY_POINTS)
                .because("the game side must not run a compound through the tag index (audit F49)");
    }

    /** Stands in for a game class doing what the rule forbids; never in the scanned set. */
    private static final class GameClassCallingTheTagIndex {
        @SuppressWarnings("unused")
        static TagHandler read(CompoundBinaryTag compound) {
            return TagHandler.fromCompound(compound);
        }
    }

    /** The same violation as a method reference. */
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
        assertTheTagRuleRejects(GameClassCallingTheTagIndex.class);
    }

    @Test
    public void theRuleRejectsAGameClassReferencingTheTagIndex() {
        assertTheTagRuleRejects(GameClassReferencingTheTagIndex.class);
    }

    private static void assertTheTagRuleRejects(Class<?> offender) {
        final JavaClasses synthetic = new ClassFileImporter().importClasses(offender);

        final AssertionError violation = assertThrows(
                AssertionError.class, () -> gameSideDoesNotReadCompoundsIntoTags().check(synthetic));

        assertTrue(violation.getMessage().contains(offender.getName()), violation.getMessage());
        assertTrue(violation.getMessage().contains("fromCompound"), violation.getMessage());
    }

    /**
     * A WARN or ERROR line, a stack trace on the standard error stream, or an exception handed to
     * {@link ExceptionManager}: the same targets as the engine, host and {@code :game-rts} rules, with
     * the same gap (the fluent {@code LOGGER.atWarn().log(…)} goes through, {@code docs/ENGINE.md} §8).
     */
    private static DescribedPredicate<JavaCall<?>> writesALogLine() {
        return target(owner(assignableTo(Logger.class))).and(target(name("warn").or(name("error"))))
                .or(target(owner(assignableTo(Throwable.class))).and(target(name("printStackTrace"))))
                .or(target(owner(assignableTo(ExceptionManager.class))).and(target(name("handleException"))));
    }

    /** No class of rush is exempt: rush writes no line of its own. */
    private static ArchRule gameSidePathsDoNotLogPerPacket() {
        return noClasses()
                .that().resideInAPackage(GAME_SIDE_PACKAGE)
                .should().callCodeUnitWhere(writesALogLine())
                .because("a client decides how often these run, so the size of the log must not follow it");
    }

    @Test
    public void gameSidePathsDoNotWriteALinePerPacket() {
        gameSidePathsDoNotLogPerPacket().check(gameSideClasses());
    }

    @Test
    public void theGameSideLogRuleRejectsALinePerAction() {
        final JavaClasses synthetic = new ClassFileImporter().importClasses(UnboundedRushLogFixture.class);

        final AssertionError violation = assertThrows(
                AssertionError.class, () -> gameSidePathsDoNotLogPerPacket().check(synthetic));

        assertTrue(violation.getMessage().contains(UnboundedRushLogFixture.class.getName()), violation.getMessage());
    }

    private static JavaClasses gameSideClasses() {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .withImportOption(new ImportOption.DoNotIncludeJars())
                .importPackages("io.github.bearisbug.synaxis.game");
    }
}
