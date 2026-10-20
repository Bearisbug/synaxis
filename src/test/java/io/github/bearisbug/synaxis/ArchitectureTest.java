package io.github.bearisbug.synaxis;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.properties.HasName;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import io.github.bearisbug.synaxis.adventure.EngineAdventure;
import io.github.bearisbug.synaxis.exception.ExceptionManager;
import io.github.bearisbug.synaxis.game.rts.GameSideFixture;
import io.github.bearisbug.synaxis.listener.UnboundedClientDrivenLogFixture;
import io.github.bearisbug.synaxis.listener.manager.PacketListenerManager;
import io.github.bearisbug.synaxis.listener.preplay.LoginListener;
import io.github.bearisbug.synaxis.network.ConnectionGate;
import io.github.bearisbug.synaxis.network.ConnectionManager;
import io.github.bearisbug.synaxis.network.UnboundedClientDrivenReportFixture;
import io.github.bearisbug.synaxis.network.packet.PacketReading;
import io.github.bearisbug.synaxis.network.player.PlayerSocketConnection;
import io.github.bearisbug.synaxis.network.player.SessionClient;
import io.github.bearisbug.synaxis.network.plugin.LoginPlugin;
import io.github.bearisbug.synaxis.network.socket.Server;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
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

    /**
     * The packages whose log volume a peer drives: the whole network stack, from the socket loops
     * through the decoder to the connection, and the listeners that handle a client packet.
     * Anything written per packet in here is written as fast as a socket can be filled, which is
     * how {@code SOAK-3} turned one WARN into 84 MiB of log in 15 minutes ({@code docs/DESIGN.md}
     * §15 and §28, {@code docs/ENGINE.md} §8).
     * <p>
     * This is a list of packages, not of client reachable paths, and the two differ: only the 12
     * packets of {@code PlayerSocketConnection.IMMEDIATE_PROCESS_PACKETS} are dispatched in here,
     * every other one is queued and handed to its listener by
     * {@code Player#interpretPacketQueue}, which sits in {@code entity} and is not watched. A line
     * written per packet over there stays green, see {@code docs/ENGINE.md} §8 blind spot 2.
     */
    private static final String[] CLIENT_DRIVEN_LOG_PACKAGES = {
            "io.github.bearisbug.synaxis.network..",
            "io.github.bearisbug.synaxis.listener..",
    };

    /**
     * A WARN or ERROR line, a stack trace dumped straight to the standard error stream, or an
     * exception handed to the host: the host picks the sink of {@link ExceptionManager} and need
     * not bound it, so a report per packet can be a stack trace per packet. The host picks the
     * sink, the caller is what decides how often it is written. INFO is on in the
     * default configuration too ({@code server-app/src/main/resources/logback.xml} sets the root to
     * INFO); the rule stops at WARN because no client driven path writes one per packet today, and
     * a new one has to be caught in review.
     */
    private static DescribedPredicate<JavaCall<?>> writesALogLine() {
        return target(owner(assignableTo(Logger.class))).and(target(name("warn").or(name("error"))))
                .or(target(owner(assignableTo(Throwable.class))).and(target(name("printStackTrace"))))
                .or(target(owner(assignableTo(ExceptionManager.class))).and(target(name("handleException"))));
    }

    /**
     * Every class of {@link #CLIENT_DRIVEN_LOG_PACKAGES} that may still report, each with the bound
     * that makes its volume independent of what a client sends:
     * <ul>
     * <li>{@link PacketReading}: at most one line per 10 seconds, carrying the count it swallowed.</li>
     * <li>{@link PlayerSocketConnection}: one line per connection for a protocol error, kicked on
     * the same branch ({@code rejectMalformedPacket}, on by default), one line per state change whose
     * packet a listener cancelled, which only the server starts, and one report per packet a
     * listener threw on. Before the configuration state that report drops the connection; from
     * there on only {@code rejectMisusedPacket} (off by default) bounds it — a hole left open,
     * tracked in {@code docs/DESIGN.md} §28. The same report per packet exists a second
     * time in {@code Player#interpretPacketQueue} for every PLAY packet, which this rule does not
     * even see because {@code entity..} is not one of the packages above.</li>
     * <li>{@link Server}: one report per connection, every read and write loop report is followed
     * by a disconnect and a break; the accept loop writes at most one line per 10 seconds for the
     * accepts that failed, and reports once per accepted socket it could not set up.</li>
     * <li>{@link ConnectionGate}: at most one line per 10 seconds, carrying the rejections it
     * counted.</li>
     * <li>{@link ConnectionManager}: one line per player, both on the join path and both followed
     * by dropping that connection.</li>
     * <li>{@link PacketListenerManager}: one line per registered packet class for the lifetime of
     * the process, a client cannot add packet classes.</li>
     * <li>{@link SessionClient}: at most one line per 10 seconds, carrying the verification
     * outcomes it counted.</li>
     * <li>{@link LoginListener}: one report per connection, to the exception handler; it kicks on
     * the same branch and {@code PlayerSocketConnection} stops the batch at a disconnect, so the
     * frames queued behind the kick are never handled.</li>
     * </ul>
     * The exemption is per class rather than per call site, which is the blind spot of this rule: a
     * second, unbounded line added inside one of these eight classes passes. The bound of each one
     * is stated in its own javadoc, which is where a reviewer has to look.
     */
    private static DescribedPredicate<HasName> boundedReporters() {
        return name(PacketReading.class.getName())
                .or(name(PlayerSocketConnection.class.getName()))
                .or(name(Server.class.getName()))
                .or(name(ConnectionGate.class.getName()))
                .or(name(SessionClient.class.getName()))
                .or(name(ConnectionManager.class.getName()))
                .or(name(PacketListenerManager.class.getName()))
                .or(name(LoginListener.class.getName()));
    }

    private static ArchRule clientDrivenPathsDoNotLogPerPacket() {
        return noClasses()
                .that().resideInAnyPackage(CLIENT_DRIVEN_LOG_PACKAGES)
                .and(not(boundedReporters()))
                .should().callCodeUnitWhere(writesALogLine())
                .because("a client decides how often these run, so the size of the log must not follow it");
    }

    @Test
    public void clientDrivenPathsDoNotWriteALinePerPacket() {
        clientDrivenPathsDoNotLogPerPacket().check(engineClasses());
    }

    /**
     * The rule is only worth having if it fires, and it cannot be violated from the main sources
     * without breaking the build, so the offender is a test only class in one of the packages.
     */
    @Test
    public void theClientDrivenLogRuleRejectsALinePerPacket() {
        JavaClasses synthetic = new ClassFileImporter().importClasses(UnboundedClientDrivenLogFixture.class);

        AssertionError violation = assertThrows(
                AssertionError.class, () -> clientDrivenPathsDoNotLogPerPacket().check(synthetic));

        assertTrue(violation.getMessage().contains(UnboundedClientDrivenLogFixture.class.getName()),
                violation.getMessage());
    }

    /**
     * The other half of the rule: an exception handed to the host is a stack trace per packet under
     * the engine default handler, and the fixture sits in {@code network} itself, the package the
     * rule would miss if it only watched the decoder and the connection.
     */
    @Test
    public void theClientDrivenLogRuleRejectsAReportPerPacket() {
        JavaClasses synthetic = new ClassFileImporter().importClasses(UnboundedClientDrivenReportFixture.class);

        AssertionError violation = assertThrows(
                AssertionError.class, () -> clientDrivenPathsDoNotLogPerPacket().check(synthetic));

        assertTrue(violation.getMessage().contains(UnboundedClientDrivenReportFixture.class.getName()),
                violation.getMessage());
    }

    /**
     * A public static field of the engine is a constant. The fork owes no binary compatibility to the code built
     * against upstream Minestom, so no field stays assignable for that reason; the one exception is
     * {@link EngineAdventure#COMPONENT_TRANSLATOR}, which a server replaces on purpose to translate components.
     */
    @Test
    public void publicStaticFieldsAreFinal() {
        fields()
                .that().arePublic().and().areStatic()
                .and().doNotHaveFullName(EngineAdventure.class.getName() + ".COMPONENT_TRANSLATOR")
                .should().beFinal()
                .because("any code in the process could reassign it")
                .check(engineClasses());
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
