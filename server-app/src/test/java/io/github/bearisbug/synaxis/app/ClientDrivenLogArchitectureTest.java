package io.github.bearisbug.synaxis.app;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.properties.HasName;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import io.github.bearisbug.synaxis.exception.ExceptionManager;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The host half of the log bound of {@code docs/DESIGN.md} §15, the counterpart of
 * {@code ArchitectureTest#clientDrivenPathsDoNotWriteALinePerPacket} in the engine.
 * <p>
 * That rule cannot see this module: it checks classes imported from the engine's own classpath,
 * which does not carry {@code :server-app}. The host is on the same client driven paths all the
 * same — the engine fires {@code PlayerPluginMessageEvent} for every
 * {@code ClientPluginMessagePacket}, and both the channel a payload names and the number of
 * payloads are the client's to pick — so the rule has to live here as well, on the same terms as
 * the stable surface gate of {@link StableSurfaceArchitectureTest}.
 */
public class ClientDrivenLogArchitectureTest {

    /**
     * The whole host package. Unlike the engine, which has to name the packets the client drives,
     * the host is small enough to watch in one piece: everything it does either runs once per
     * process, once per connection, or once per inbound payload, and only the last one needs a
     * bound.
     */
    private static final String HOST_PACKAGE = "io.github.bearisbug.synaxis.app..";

    /**
     * Same targets as the engine rule: a WARN or ERROR line, a stack trace on the standard error
     * stream, or an exception handed to {@link ExceptionManager}, whose default sink is
     * {@code Throwable::printStackTrace}.
     */
    private static DescribedPredicate<JavaCall<?>> writesALogLine() {
        return target(owner(assignableTo(Logger.class))).and(target(name("warn").or(name("error"))))
                .or(target(owner(assignableTo(Throwable.class))).and(target(name("printStackTrace"))))
                .or(target(owner(assignableTo(ExceptionManager.class))).and(target(name("handleException"))));
    }

    /**
     * The host classes that may still write a line themselves, each with the bound that keeps its
     * volume off the client's hands:
     * <ul>
     * <li>{@link LogBudget}: the bound itself, at most one line per interval with the count it
     * swallowed.</li>
     * <li>{@link Main}: startup and shutdown, a fixed number of lines per process.</li>
     * <li>{@link PluginHost}: one line per plugin per enable or disable, and a plugin list a
     * client cannot add to.</li>
     * <li>{@link EventLedger}: one line per ledger event that could not be appended, and the only
     * ledger event of M2 is {@code plugin_enabled}.</li>
     * <li>{@link AdmissionGate}: one line per connection, on the configuration phase event that
     * happens once per login; the failure branch kicks on the same branch.</li>
     * <li>{@link PluginSchedulerImpl}: one line per failing task run. A plugin picks that period
     * when it schedules the task, a client cannot make a task run more often.</li>
     * </ul>
     * {@link PluginChannelRouter} and {@link PluginEventsImpl} are deliberately absent: both sit on
     * an inbound payload path, so both report through a {@link LogBudget} instead, and an
     * exemption per class would have let a second unbounded line in next to the budgeted one —
     * blind spot ① of the engine rule ({@code docs/ENGINE.md} §8), which this module does not
     * inherit.
     */
    private static DescribedPredicate<HasName> boundedReporters() {
        return name(LogBudget.class.getName())
                .or(name(Main.class.getName()))
                .or(name(PluginHost.class.getName()))
                .or(name(EventLedger.class.getName()))
                .or(name(AdmissionGate.class.getName()))
                .or(name(PluginSchedulerImpl.class.getName()));
    }

    private static ArchRule hostPathsDoNotLogPerPacket() {
        return noClasses()
                .that().resideInAPackage(HOST_PACKAGE)
                .and(not(boundedReporters()))
                .should().callCodeUnitWhere(writesALogLine())
                .because("a client decides how often these run, so the size of the log must not follow it");
    }

    @Test
    public void hostPathsDoNotWriteALinePerPacket() {
        hostPathsDoNotLogPerPacket().check(hostClasses());
    }

    /**
     * The rule is only worth having if it fires, and a main source violation would break the build
     * it is meant to guard, so the offender is a test only class in the same package.
     */
    @Test
    public void theHostLogRuleRejectsALinePerPayload() {
        JavaClasses synthetic = new ClassFileImporter().importClasses(UnboundedHostLogFixture.class);

        AssertionError violation = assertThrows(
                AssertionError.class, () -> hostPathsDoNotLogPerPacket().check(synthetic));

        assertTrue(violation.getMessage().contains(UnboundedHostLogFixture.class.getName()),
                violation.getMessage());
    }

    private static JavaClasses hostClasses() {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .withImportOption(new ImportOption.DoNotIncludeJars())
                .importPackages(PluginChannelRouter.class.getPackageName());
    }
}
