package io.github.bearisbug.synaxis.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import org.jetbrains.annotations.ApiStatus;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine members {@code :plugin-api} hands to plugins. They live in the engine root module,
 * which the ABI gate does not cover, so their signatures are pinned here instead.
 */
public class EngineInternalsArchitectureTest {

    /**
     * The engine entry point {@code plugin-api} re-exports without the ABI gate covering it
     * (REQ-M2-025, §24). The gate applies to {@code :plugin-api} and {@code :protocol}; this method
     * lives in the engine root module, so nothing mechanical noticed a change to it before. Adding
     * a second such entry point means adding it here, in the same change that signs it off.
     * <p>
     * Read from the class file, not through reflection: {@link ApiStatus.Internal} is retained at
     * {@code CLASS} level only, so {@code Method#isAnnotationPresent} answers false whether or not
     * the annotation is there.
     */
    @Test
    public void theEnginePositionEntryPointStillHasTheSignaturePluginsWereGiven() {
        JavaClass entity = new ClassFileImporter().importClasses(Entity.class).get(Entity.class);
        Optional<JavaMethod> found = entity.tryGetMethod("moveTo", Pos.class);
        assertTrue(found.isPresent(),
                "Entity#moveTo(Pos) is part of the stable surface: removing or renaming it is a"
                        + " breaking change of plugin-api and goes through §8 evolution and deprecation");
        JavaMethod moveTo = found.get();

        assertTrue(moveTo.getModifiers().contains(JavaModifier.PUBLIC), "moveTo stopped being public");
        assertTrue(moveTo.getRawReturnType().isEquivalentTo(void.class), "moveTo changed what it returns");
        assertFalse(moveTo.isAnnotatedWith(ApiStatus.Internal.class),
                "moveTo was marked internal, which takes back the entry point plugins were given"
                        + " instead of deprecating it");
    }
}
