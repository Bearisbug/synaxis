package io.github.bearisbug.synaxis.network.player;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** How a 200 of {@code hasJoined} is read ({@code docs/DESIGN-PROD.md} {@code API-PROD-002}). */
public class SessionClientTest {

    @Test
    public void aProfileKeepsItsPropertiesAsSent() {
        final var verified = assertInstanceOf(SessionClient.Result.Verified.class, SessionClient.profile("""
                {"id":"069a79f444e94726a5befca90e38aaf5","name":"Notch","properties":[
                 {"name":"textures","value":"dGV4dHVyZXM=","signature":"c2lnbmF0dXJl"},
                 {"name":"unsigned","value":"eA=="}]}"""));
        assertEquals(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), verified.profile().uuid());
        assertEquals("Notch", verified.profile().name());
        assertEquals(List.of(new GameProfile.Property("textures", "dGV4dHVyZXM=", "c2lnbmF0dXJl"),
                new GameProfile.Property("unsigned", "eA==", null)), verified.profile().properties());
    }

    @Test
    public void aTwoHundredThatIsNotAProfileIsUnavailableNotAVerdict() {
        for (String body : List.of("", "not json", "{}", "{\"id\":\"xyz\",\"name\":\"Notch\"}",
                "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"AMuchTooLongPlayerName\"}")) {
            assertInstanceOf(SessionClient.Result.Unavailable.class, SessionClient.profile(body), body);
        }
    }
}
