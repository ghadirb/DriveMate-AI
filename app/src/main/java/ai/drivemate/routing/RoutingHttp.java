package ai.drivemate.routing;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class RoutingHttp {
    public static JSONObject getJson(String url) throws Exception {
        return getJson(url, null, null);
    }

    public static JSONObject getJson(String url, String headerName, String headerValue) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(9000);
        connection.setReadTimeout(12000);
        connection.setRequestMethod("GET");
        if (headerName != null && headerValue != null && !headerValue.trim().isEmpty()) {
            connection.setRequestProperty(headerName, headerValue);
        }
        connection.setRequestProperty("Accept", "application/json");

        int code = connection.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream()
        ));
        StringBuilder body = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            body.append(line);
        }
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + ": " + body);
        }
        return new JSONObject(body.toString());
    }

    static JSONObject postJson(String url, String headerName, String headerValue, JSONObject payload) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(9000);
        connection.setReadTimeout(12000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        if (headerName != null && headerValue != null && !headerValue.trim().isEmpty()) {
            connection.setRequestProperty(headerName, headerValue);
        }
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] data = payload.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(data.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(data);
        }
        int code = connection.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream(),
                StandardCharsets.UTF_8
        ));
        StringBuilder body = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) body.append(line);
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + ": " + body);
        return new JSONObject(body.toString());
    }

    static JSONObject postFormJson(String url, String parameterName, String value) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        // Overpass is the only caller; a wide-radius POI query can legitimately take longer than
        // a small nearby lookup, so this allows enough headroom for the interpreter's own
        // [timeout:20] to finish and reply before the client itself gives up.
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(21000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
        connection.setRequestProperty("User-Agent", "DriveMate-AI/1.0");
        byte[] data = (URLEncoder.encode(parameterName, "UTF-8") + "="
                + URLEncoder.encode(value, "UTF-8")).getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(data.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(data);
        }
        int code = connection.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream(),
                StandardCharsets.UTF_8
        ));
        StringBuilder body = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) body.append(line);
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + ": " + body);
        return new JSONObject(body.toString());
    }
}
