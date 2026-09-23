package io.github.bearisbug.synaxis.app;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.properties.HasName;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.app.store.ShardSession;
import io.github.bearisbug.synaxis.app.transfer.TicketVerifier;
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
     * <p>
     * It matches the call by name on {@link Logger}, so the fluent form
     * {@code LOGGER.atWarn().log(…)} goes through unseen — the same gap as the engine rule has,
     * registered in {@code docs/ENGINE.md} §8.
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
     * <li>{@link Main}: startup and shutdown, a fixed number of lines per process.</li>
     * <li>{@link PluginHost}: one line per plugin per enable or disable, and a plugin list a
     * client cannot add to.</li>
     * <li>{@link AdmissionGate}: one line per connection, on the configuration phase event that
     * happens once per login; the failure branch kicks on the same branch.</li>
     * <li>{@link PluginSchedulerImpl}: one line per failing task run. A plugin picks both the
     * period and the number of tasks; nothing a client sends adds a task on its own, and a plugin
     * that schedules one per inbound packet would be writing the unbounded line itself.</li>
     * <li>{@link CrashReports}: it prints stack traces into a crash report file, not into the log,
     * and one report per fatal event — the exception handler writes one per process, the tick
     * watchdog one per stall. Its own failure goes through a budget.</li>
     * <li>{@link PlayerStore}: at most one line per login that cannot be loaded (the login rate is
     * bounded by the L2 bucket of {@code docs/DESIGN-PROD.md} {@code API-PROD-003}), per character
     * fenced or orphaned, and per character left unsaved by a shutdown. Failures whose count a
     * client or a plugin picks — a save or a commit going wrong, Redis down — go through a
     * budget.</li>
     * <li>{@link ShardSession}: one line per state change of the shard session, which the database
     * and the shutdown drive, never a client; failed renewals go through a budget.</li>
     * <li>{@link TicketVerifier}: one line per transfer login it refuses, whose rate the L2
     * transfer bucket of {@code API-PROD-003} bounds; the one ERROR is the version assertion, once
     * per ticket at most.</li>
     * </ul>
     * {@link PluginChannelRouter}, {@link PluginEventsImpl} and {@link EventLedger} are
     * deliberately absent: all three sit on a path whose rate a client picks — the first two on an
     * inbound payload, the ledger because {@code PluginAnalytics} hands {@code record} to every
     * plugin and {@code docs/DESIGN-M2.md} §20 asks for one event per inbound order packet — so all
     * three report through a {@link io.github.bearisbug.synaxis.plugin.LogBudget} instead. An
     * exemption per class would have let a second unbounded line in next to the budgeted one —
     * blind spot ① of the engine rule ({@code docs/ENGINE.md} §8), which those three therefore
     * escape.
     * <p>
     * The eight above do not: this list is by class, exactly like the engine's, so a second line
     * whose rate a client picks, added inside one of them, stays green. Measured, not assumed — a
     * listener on a PLAY phase event writing a plain {@code LOGGER.warn}, put inside
     * {@link AdmissionGate}, does not turn this rule red. Registered as a remaining blind spot in
     * {@code docs/DESIGN.md} §28.
     */
    private static DescribedPredicate<HasName> boundedReporters() {
        return name(Main.class.getName())
                .or(name(PluginHost.class.getName()))
                .or(name(AdmissionGate.class.getName()))
                .or(name(PluginSchedulerImpl.class.getName()))
                .or(name(CrashReports.class.getName()))
                .or(name(PlayerStore.class.getName()))
                .or(name(ShardSession.class.getName()))
                .or(name(TicketVerifier.class.getName()));
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

    /**
     * Both this import and {@link #HOST_PACKAGE} stop at the {@code app} package tree, which today
     * holds every main source file of this module. A second top level package added under
     * {@code :server-app} would be neither imported nor matched, and would go unwatched without
     * anything going red — registered as a remaining blind spot in {@code docs/DESIGN.md} §28.
     */
    private static JavaClasses hostClasses() {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .withImportOption(new ImportOption.DoNotIncludeJars())
                .importPackages(PluginChannelRouter.class.getPackageName());
    }
}
