package ai.drivemate.ai;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Android knows only the Apps Script URL; AvalAI credentials and model selection stay server-side. */
public final class CarDiagnosisClient {
    public interface Callback { void onSuccess(String analysis); void onFailure(String message); }

    private CarDiagnosisClient() { }

    public static void analyze(String proxyUrl, JSONObject payload, Callback callback) {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(proxyUrl).openConnection();
                c.setRequestMethod("POST"); c.setConnectTimeout(15_000); c.setReadTimeout(90_000); c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream out = c.getOutputStream()) { out.write(payload.toString().getBytes(StandardCharsets.UTF_8)); }
                int code = c.getResponseCode();
                String response = read(code < 300 ? c.getInputStream() : c.getErrorStream());
                if (code >= 300) throw new IllegalStateException("HTTP " + code + ": " + response);
                JSONObject json = new JSONObject(response);
                if (!json.optBoolean("ok", false)) throw new IllegalStateException(json.optString("error", "پاسخ نامعتبر از سرویس"));
                String analysis = json.optString("analysis", "").trim();
                if (analysis.isEmpty()) throw new IllegalStateException("پاسخ تحلیلی دریافت نشد.");
                callback.onSuccess(analysis);
            } catch (Exception error) {
                String message = error.getMessage() == null ? "خطای نامشخص در ارتباط با سرویس" : error.getMessage();
                if (message.contains("timed out")) message = "زمان پاسخ سرویس تمام شد؛ اتصال را بررسی و دوباره تلاش کنید.";
                callback.onFailure(message.length() > 500 ? message.substring(0, 500) : message);
            }
        }).start();
    }

    private static String read(java.io.InputStream input) throws Exception {
        if (input == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line; while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }
}
