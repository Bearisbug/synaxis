package io.github.bearisbug.synaxis.instance.light;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import io.github.bearisbug.synaxis.instance.Instance;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.MonitorInstruction;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * REQ-M3-004: the light engine runs on a {@link WorldView} alone. It must not reach an {@link Instance} or a
 * chunk, and it must not lock: no {@code synchronized} method and no {@code synchronized} block, which bytecode
 * rules cannot see and the class file parser here can.
 */
public class LightEngineIsolationTest {
    private static final List<Class<?>> ENGINE = List.of(LightEngine.class, LightResult.class, WorldView.class);

    private static final ArchRule NO_INSTANCE = noClasses()
            .should().dependOnClassesThat(resideInAPackage("io.github.bearisbug.synaxis.instance"))
            .because("LightEngine runs on a WorldView, not on an instance (REQ-M3-004)");

    @Test
    public void theEngineReachesNoInstance() {
        NO_INSTANCE.check(engineClasses());
    }

    @Test
    public void theInstanceRuleRejectsAnInstanceReference() {
        final JavaClasses fixture = new ClassFileImporter().importClasses(ReachesForTheInstance.class);
        assertThrows(AssertionError.class, () -> NO_INSTANCE.check(fixture));
    }

    @Test
    public void theEngineTakesNoMonitor() throws IOException {
        final List<String> locks = new ArrayList<>();
        for (Class<?> type : engineClassesAndNested()) locks.addAll(monitors(type));
        assertEquals(List.of(), locks);
    }

    @Test
    public void theMonitorCheckSeesASynchronizedBlockAndMethod() throws IOException {
        assertEquals(2, monitors(Locks.class).size(), monitors(Locks.class).toString());
    }

    private static JavaClasses engineClasses() {
        return new ClassFileImporter().importClasses(engineClassesAndNested());
    }

    private static List<Class<?>> engineClassesAndNested() {
        final List<Class<?>> all = new ArrayList<>();
        for (Class<?> type : ENGINE) {
            all.add(type);
            all.addAll(List.of(type.getDeclaredClasses()));
        }
        return all;
    }

    /** One entry per synchronized method and per method holding a monitorenter. */
    private static List<String> monitors(Class<?> type) throws IOException {
        final byte[] bytes;
        try (InputStream in = Objects.requireNonNull(type.getClassLoader().getResourceAsStream(type.getName().replace('.', '/') + ".class"))) {
            bytes = in.readAllBytes();
        }
        final ClassModel model = ClassFile.of().parse(bytes);
        final List<String> found = new ArrayList<>();
        for (MethodModel method : model.methods()) {
            final String name = type.getName() + "#" + method.methodName().stringValue();
            if (method.flags().has(AccessFlag.SYNCHRONIZED)) found.add(name + " is synchronized");
            method.code().ifPresent(code -> code.forEach(element -> {
                if (element instanceof MonitorInstruction monitor && monitor.opcode() == Opcode.MONITORENTER) {
                    found.add(name + " enters a monitor");
                }
            }));
        }
        return found;
    }

    @SuppressWarnings("unused")
    private static final class ReachesForTheInstance {
        static int minY(Instance instance) {
            return instance.getCachedDimensionType().minY();
        }
    }

    @SuppressWarnings("unused")
    private static final class Locks {
        private final Object lock = new Object();
        private int count;

        synchronized void method() {
            count++;
        }

        void block() {
            synchronized (lock) {
                count++;
            }
        }
    }
}
