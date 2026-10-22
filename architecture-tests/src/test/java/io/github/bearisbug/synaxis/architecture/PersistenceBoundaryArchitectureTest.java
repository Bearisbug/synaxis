package io.github.bearisbug.synaxis.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import io.github.bearisbug.synaxis.architecture.fixture.ClassReachingForTheDatabase;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Only the host touches the database and Redis ({@code docs/DESIGN-PROD.md} {@code ADR-PROD-012},
 * §24 ArchUnit ①): the engine, the stable surface, the protocol and the map modules depend on no
 * JDBC, pool, migration or Redis type. The fencing of every write is only a guarantee while no code
 * but the host's store can write a character.
 * <p>
 * It lives here because only this project has the engine, {@code :plugin-api}, {@code :protocol},
 * {@code :synx}, {@code :baker} and the host on one classpath; a rule in the engine could not see the
 * other modules.
 */
public class PersistenceBoundaryArchitectureTest {

    private static ArchRule onlyTheHostTouchesTheStore() {
        return noClasses()
                .that().resideInAPackage("io.github.bearisbug.synaxis..")
                .and().resideOutsideOfPackage("io.github.bearisbug.synaxis.app..")
                .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..", "org.postgresql..",
                        "com.zaxxer..", "org.flywaydb..", "redis.clients..")
                .because("a plugin or the engine holding a connection could write around the lease and version check");
    }

    @Test
    public void onlyTheHostDependsOnTheDatabaseOrRedis() {
        onlyTheHostTouchesTheStore().check(new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("io.github.bearisbug.synaxis"));
    }

    @Test
    public void theRuleRejectsAClassOutsideTheHostHoldingAConnection() {
        final JavaClasses fixture = new ClassFileImporter().importClasses(ClassReachingForTheDatabase.class);

        final AssertionError violation = assertThrows(AssertionError.class,
                () -> onlyTheHostTouchesTheStore().check(fixture));

        assertTrue(violation.getMessage().contains("java.sql.Connection"), violation.getMessage());
    }
}
