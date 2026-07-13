package ai.drivemate.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class AiAssistant {
    public interface AnswerCallback { void onAnswer(String answer); }

    private RuntimeKeys keys = new RuntimeKeys();
    private final String buildTimeKey;

    public AiAssistant(String apiKey) { this.buildTimeKey = apiKey; }
    public void setRuntimeKeys(RuntimeKeys keys) { if (keys != null) this.keys = keys; }

    public void answer(String question, AnswerCallback callback) {
        new Thread(() -> {
            String normalized = question == null ? "" : question;
            try { callback.onAnswer(onlineAnswer(normalized)); }
            catch (Exception ex) { callback.onAnswer(offlineAnswer(normalized)); }
        }).start();
    }

    private String onlineAnswer(String question) throws Exception {
        String gapKey = first(keys.get("GAPGPT_API_KEY"), keys.get("AI_API_KEY"), buildTimeKey);
        Exception gapError = null;
        if (gapKey != null) {
            try { return chat("https://api.gapgpt.app/v1/chat/completions", gapKey, "gpt-5-nano", question); }
            catch (Exception error) { gapError = error; }
        }
        String liaraKey = keys.get("LIARA_API_KEY");
        if (liaraKey != null) return chat("https://ai.liara.ir/api/69467b6ba99a2016cac892e1/v1/chat/completions", liaraKey, "openai/gpt-5-nano", question);
        if (gapError != null) throw gapError;
        throw new IllegalStateException("no AI key");
    }

    private String chat(String endpoint, String apiKey, String model, String question) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", "تو دستیار رانندگی فارسی DriveMate هستی. پاسخ کوتاه، ایمن و کاربردی بده."));
        messages.put(new JSONObject().put("role", "user").put("content", question));
        body.put("messages", messages);
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(20000); c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + apiKey); c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = c.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getResponseCode() < 300 ? c.getInputStream() : c.getErrorStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(); String line; while ((line = r.readLine()) != null) sb.append(line);
        if (c.getResponseCode() >= 300) throw new IllegalStateException(sb.toString());
        return new JSONObject(sb.toString()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "پاسخی دریافت نشد.");
    }

    private String first(String... values) { for (String v : values) if (v != null && !v.trim().isEmpty()) return v.trim(); return null; }

    private String offlineAnswer(String question) {
        if (question.contains("پمپ بنزین")) return "برای پیدا کردن پمپ بنزین، کنار مسیر اصلی توقف ایمن داشته باشید و جست‌وجوی نقشه را فعال کنید.";
        if (question.contains("خلوت")) return "برای مسیر خلوت‌تر باید مسیر جایگزین با ترافیک زنده بررسی شود. فعلاً مسیر ذخیره‌شده حفظ می‌شود.";
        if (question.contains("چرا")) return "معمولاً به‌خاطر خروج از مسیر، ترافیک یا خطای GPS مسیر دوباره محاسبه می‌شود.";
        return "فعلاً پاسخ آفلاین فعال است؛ پس از دریافت کلید آنلاین، مدل GapGPT با اولویت اول استفاده می‌شود.";
    }
}
