package io.github.bearisbug.synaxis.entity;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.packet.server.play.PlayerInfoUpdatePacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * {@code docs/DESIGN-PROD.md} {@code REQ-PROD-003}: the signed {@code textures} property the session
 * server answered with is the skin other players are sent, and nothing on the way asks Mojang for a
 * skin a second time.
 */
@EnvTest
public class ProfileSkinIntegrationTest {

    @Test
    public void theTexturesOfTheProfileReachOtherPlayers(Env env) {
        final Instance instance = env.createFlatInstance();
        final var viewer = env.createConnection();
        final var infos = viewer.trackIncoming(PlayerInfoUpdatePacket.class);
        viewer.connect(instance, new Pos(0, 42, 0));

        final UUID uuid = UUID.randomUUID();
        env.createConnection(new GameProfile(uuid, "Skinned",
                List.of(new GameProfile.Property("textures", "base64-textures", "base64-signature"))))
                .connect(instance, new Pos(1, 42, 0));

        final var expected = List.of(new PlayerInfoUpdatePacket.Property("textures", "base64-textures", "base64-signature"));
        infos.assertAnyMatch(packet -> packet.actions().contains(PlayerInfoUpdatePacket.Action.ADD_PLAYER)
                && packet.entries().stream().anyMatch(entry -> entry.uuid().equals(uuid)
                && entry.properties().equals(expected)));
    }

    @Test
    public void noEngineCodeLooksASkinUpOnItsOwn() {
        final JavaClasses engine = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.bearisbug.synaxis");
        noClasses().that().doNotHaveFullyQualifiedName(PlayerSkin.class.getName())
                .should().callMethod(PlayerSkin.class, "fromUsername", String.class)
                .orShould().callMethod(PlayerSkin.class, "fromUuid", String.class)
                .because("the login path takes the skin from the verified profile (REQ-PROD-003)")
                .check(engine);
    }
}
