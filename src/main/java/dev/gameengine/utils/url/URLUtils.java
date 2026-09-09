package dev.gameengine.utils.url;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public final class URLUtils {
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 5_000;

    private URLUtils() {

    }

    public static String getText(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        //add headers to the connection, or check the status if desired..
        // An unresponsive endpoint would otherwise block the calling thread forever
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);

        // handle error response code it occurs
        final int responseCode = connection.getResponseCode();
        final InputStream inputStream;
        if (200 <= responseCode && responseCode <= 299) {
            inputStream = connection.getInputStream();
        } else {
            inputStream = connection.getErrorStream();
        }
        // getErrorStream() is null when the error response carries no body, an empty rate limit
        // response for instance
        if (inputStream == null) return "";

        StringBuilder response = new StringBuilder();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String currentLine;
            while ((currentLine = in.readLine()) != null)
                response.append(currentLine);
        }

        return response.toString();
    }
}
