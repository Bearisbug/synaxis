package io.github.bearisbug.synaxis.utils.mojang;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.bearisbug.synaxis.utils.StringUtils;
import io.github.bearisbug.synaxis.utils.url.URLUtils;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Utils class using mojang API.
 */
public final class MojangUtils {
    private static final String FROM_UUID_URL = "https://sessionserver.mojang.com/session/minecraft/profile/%s?unsigned=false";
    private static final String FROM_USERNAME_URL = "https://api.minecraftservices.com/minecraft/profile/lookup/name/%s";

    /**
     * Gets a player's UUID from their username
     *
     * @param username The players username
     * @return The {@link UUID}
     * @throws IOException with text detailing the exception
     */
    @Blocking
    public static UUID getUUID(String username) throws IOException {
        // Thanks stackoverflow: https://stackoverflow.com/a/19399768/13247146
        return UUID.fromString(
                formatUUID(retrieve(String.format(FROM_USERNAME_URL, encode(validateUsername(username)))).get("id").getAsString())
        );
    }

    /**
     * Gets a player's username from their UUID
     *
     * @param playerUUID The {@link UUID} of the player
     * @return The player's username
     * @throws IOException with text detailing the exception
     */
    @Blocking
    public static String getUsername(UUID playerUUID) throws IOException {
        return retrieve(String.format(FROM_UUID_URL, playerUUID)).get("name").getAsString();
    }

    /**
     * Gets a {@link JsonObject} with the response from the mojang API
     *
     * @param uuid The UUID as a {@link UUID}
     * @return The {@link JsonObject} or {@code null} if the mojang API is down, rate limited or the UUID is invalid
     */
    @Blocking
    public static @Nullable JsonObject fromUuid(UUID uuid) {
        try {
            return retrieve(String.format(FROM_UUID_URL, uuid));
        } catch (IOException _) {
            return null;
        }
    }

    /**
     * Gets a {@link JsonObject} with the response from the mojang API
     *
     * @param uuid The UUID as a {@link String}
     * @return The {@link JsonObject} or {@code null} if the mojang API is down, rate limited or the UUID is invalid
     */
    @Blocking
    public static @Nullable JsonObject fromUuid(String uuid) {
        final UUID parsed;
        try {
            parsed = UUID.fromString(formatUUID(uuid));
        } catch (IllegalArgumentException _) {
            return null;
        }
        return fromUuid(parsed);
    }

    /**
     * Gets a {@link JsonObject} with the response from the mojang API
     *
     * @param username The username as a {@link String}
     * @return The {@link JsonObject} or {@code null} if the mojang API is down, rate limited or the username is invalid
     */
    @Blocking
    public static @Nullable JsonObject fromUsername(String username) {
        if (!StringUtils.isValidUsername(username)) return null;
        try {
            return retrieve(String.format(FROM_USERNAME_URL, encode(username)));
        } catch (IOException _) {
            return null;
        }
    }

    private static String formatUUID(String uuid) {
        return uuid.replaceFirst(
                "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
                "$1-$2-$3-$4-$5"
        );
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String validateUsername(String username) throws IOException {
        if (!StringUtils.isValidUsername(username)) {
            throw new IOException("Invalid username: " + username);
        }
        return username;
    }

    /**
     * Gets the JsonObject from a URL, expects a mojang player URL so the errors might not make sense if it is not
     *
     * @param url The url to retrieve
     * @return The {@link JsonObject} of the result
     * @throws IOException with the text detailing the exception
     */
    private static JsonObject retrieve(String url) throws IOException {
        // Retrieve from the rate-limited Mojang API
        final String response = URLUtils.getText(url);
        // An empty body is a profile that does not exist (204), or an error response without a body such as a 429:
        // the two cannot be told apart here
        if (response.isEmpty())
            throw new IOException("Empty response from the Mojang API (no such profile, or a bodyless error such as HTTP 429)");
        final JsonElement element;
        try {
            element = JsonParser.parseString(response);
        } catch (RuntimeException e) {
            // Not JSON at all, an HTML error page for instance
            throw new IOException("Malformed response from the Mojang API", e);
        }
        if (!element.isJsonObject()) throw new IOException("Unexpected response from the Mojang API: " + response);
        JsonObject jsonObject = element.getAsJsonObject();
        if (jsonObject.has("errorMessage")) {
            throw new IOException(jsonObject.get("errorMessage").getAsString());
        }
        return jsonObject;
    }
}
