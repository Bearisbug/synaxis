package io.github.bearisbug.synaxis.game.rts;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodReference;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import io.github.bearisbug.synaxis.tag.Tag;
import io.github.bearisbug.synaxis.tag.TagHandler;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate the game side owes the engine, see {@code docs/DESIGN-M2.md} §24 rule ③ (REQ-M2-015).
 * <p>
 * It lives here rather than in the engine's own {@code ArchitectureTest} because that one imports
 * the classes on the engine's classpath, where no game class can ever be: written there the rule
 * would be green forever.
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
     * enforce nothing. The game side pays for it by not using the tag system at all, which costs it
     * nothing: the gameplay data model is its own and travels over custom payloads.
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

    private static JavaClasses gameSideClasses() {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .withImportOption(new ImportOption.DoNotIncludeJars())
                .importPackages("io.github.bearisbug.synaxis.game");
    }
}
