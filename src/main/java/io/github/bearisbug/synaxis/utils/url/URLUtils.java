package io.github.bearisbug.synaxis.utils.url;

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

    /**
     * Reads the body of a GET request as text. A response outside 2xx gives its error body, the status code is not
     * exposed; a response without a body (a 204, or an error such as an empty 429) gives an empty string. Connecting
     * and reading each time out after 5 s.
     *
     * @param url the url
     * @return the body of the response, its lines joined without separators
     * @throws IOException if the request fails or times out
     */
    public static String getText(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        // An unresponsive endpoint would otherwise block the calling thread forever
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);

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
