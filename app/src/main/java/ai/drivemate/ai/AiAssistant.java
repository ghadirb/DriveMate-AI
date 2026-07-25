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

    /** Distinguishes a provider answer from the built-in offline wording. */
    public static final class AnswerResult {
        public final String text;
        public final boolean online;

        AnswerResult(String text, boolean online) {
            this.text = text;
            this.online = online;
        }
    }

    private RuntimeKeys keys = new RuntimeKeys();
    private final String buildTimeKey;

    public AiAssistant(String apiKey) { this.buildTimeKey = apiKey; }
    public void setRuntimeKeys(RuntimeKeys keys) { if (keys != null) this.keys = keys; }

    public void answer(String question, AnswerCallback callback) {
        answer(question, "", callback);
    }

    public void answer(String question, String drivingContext, AnswerCallback callback) {
        new Thread(() -> callback.onAnswer(answerNow(question, drivingContext))).start();
    }

    /** Performs one complete provider attempt. Call from a worker thread, never the UI thread. */
    public String answerNow(String question, String drivingContext) {
        return answerNowResult(question, drivingContext).text;
    }

    /** Performs one complete provider attempt and reports whether a provider actually answered. */
    public AnswerResult answerNowResult(String question, String drivingContext) {
        String normalized = question == null ? "" : question;
        try { return new AnswerResult(onlineAnswer(normalized, drivingContext), true); }
        catch (Exception error) {
            android.util.Log.w("DriveMateAI", "online answer failed, using offline fallback: " + error, error);
            return new AnswerResult(offlineAnswer(normalized), false);
        }
    }

    private String onlineAnswer(String question, String drivingContext) throws Exception {
        String gapKey = first(keys.get("GAPGPT_API_KEY"), keys.get("AI_API_KEY"), buildTimeKey);
        Exception gapError = null;
        if (gapKey != null) {
            // gpt-4o-mini is documented by GapGPT and is better suited to short, time-bound driving prompts.
            try { return chat("https://api.gapgpt.app/v1/chat/completions", gapKey, "gpt-4o-mini", question, drivingContext); }
            catch (Exception error) { gapError = error; }
        }
        String liaraKey = keys.get("LIARA_API_KEY");
        if (liaraKey != null) return chat(liaraBaseUrl() + "/chat/completions", liaraKey, "openai/gpt-5-nano", question, drivingContext);
        if (gapError != null) throw gapError;
        throw new IllegalStateException("no AI key");
    }

    private String chat(String endpoint, String apiKey, String model, String question, String drivingContext) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", "تو دستیار رانندگی فارسی DriveMate هستی. پاسخ را فقط فارسی، حداکثر دو جمله و کمتر از ۳۵ کلمه بده. متن برای پخش صوتی است؛ مقدمه، فهرست و توضیح طولانی نده. برای امور ایمنی راننده را به توقف امن تشویق کن. بدون دادهٔ زنده، دربارهٔ ترافیک یا مکان‌های نزدیک ادعای قطعی نکن. زمینه سفر: " + (drivingContext == null ? "" : drivingContext)));
        messages.put(new JSONObject().put("role", "user").put("content", question));
        body.put("messages", messages);
        body.put("max_tokens", 60);
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
        c.setConnectTimeout(3000); c.setReadTimeout(4500); c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + apiKey); c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = c.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        try {
            int code = c.getResponseCode();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(code < 300 ? c.getInputStream() : c.getErrorStream(), StandardCharsets.UTF_8))) {
                String line; while ((line = r.readLine()) != null) sb.append(line);
            }
            if (code >= 300) throw new IllegalStateException("HTTP " + code);
            return new JSONObject(sb.toString()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "پاسخی دریافت نشد.");
        } finally { c.disconnect(); }
    }

    private String first(String... values) { for (String v : values) if (v != null && !v.trim().isEmpty()) return v.trim(); return null; }

    private String liaraBaseUrl() {
        String baseUrl = keys.get("LIARA_BASE_URL");
        if (baseUrl == null || baseUrl.trim().isEmpty()) baseUrl = "https://ai.liara.ir/api/69467b6ba99a2016cac892e1/v1";
        return baseUrl.replaceAll("/+$", "");
    }

    private String offlineAnswer(String question) {
        if (question.contains("پمپ بنزین")) return "نزدیک‌ترین پمپ بنزین در حال جست‌وجو است.";
        if (question.contains("خلوت") || question.contains("ترافیک")) return "دادهٔ ترافیک زنده در دسترس نیست.";
        if (question.contains("چرا")) return "احتمالاً به‌دلیل خروج از مسیر یا تغییر GPS است.";
        return "پاسخ آنلاین در دسترس نیست.";
    }
}
