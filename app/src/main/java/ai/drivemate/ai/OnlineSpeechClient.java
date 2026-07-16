package ai.drivemate.ai;

import android.content.Context;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** GapGPT speech-to-text and text-to-speech client. All network calls run off the UI thread. */
public class OnlineSpeechClient {
    private static final String TAG = "DriveMateVoice";
    public interface TextCallback { void onResult(String text); void onError(String message); }
    public interface SpeechCallback { void onPlayed(); void onError(); }

    private final Context context;
    private RuntimeKeys keys = new RuntimeKeys();
    private String buildKey;
    private MediaRecorder recorder;
    private File recording;
    private MediaPlayer player;

    public OnlineSpeechClient(Context context, String buildKey) {
        this.context = context.getApplicationContext();
        this.buildKey = buildKey;
    }

    public void setRuntimeKeys(RuntimeKeys keys) { if (keys != null) this.keys = keys; }
    public boolean canUseOnlineSpeech() { return gapKey() != null || liaraKey() != null; }

    public boolean startRecording() {
        try {
            recording = new File(context.getCacheDir(), "voice-command.m4a");
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setAudioSamplingRate(44100);
            recorder.setOutputFile(recording.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            return true;
        } catch (Exception error) {
            Log.e(TAG, "Could not start microphone recording", error);
            releaseRecorder();
            return false;
        }
    }

    public void stopAndTranscribe(TextCallback callback) {
        if (recorder == null || recording == null) { callback.onError("ضبط صدا شروع نشده است."); return; }
        try { recorder.stop(); } catch (RuntimeException ignored) { }
        releaseRecorder();
        File audio = recording;
        new Thread(() -> {
            try { callback.onResult(transcribeWithFallback(audio)); }
            catch (Exception error) {
                Log.e(TAG, "Online speech recognition failed", error);
                callback.onError("تبدیل گفتار آنلاین انجام نشد: " + safeMessage(error));
            }
        }).start();
    }

    public void speak(String text) {
        speak(text, null);
    }

    /** Uses GapGPT TTS first and reports failure so callers can use a local accessibility fallback. */
    public void speak(String text, SpeechCallback callback) {
        String key = gapKey();
        if (key == null || text == null || text.trim().isEmpty()) {
            if (callback != null) callback.onError();
            return;
        }
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject().put("model", "tts-1").put("voice", "alloy").put("input", text);
                HttpURLConnection connection = open("https://api.gapgpt.app/v1/audio/speech", key);
                try {
                    connection.setDoOutput(true);
                    try (OutputStream out = connection.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
                    if (connection.getResponseCode() >= 300) { notifySpeechError(callback); return; }
                    File output = new File(context.getCacheDir(), "answer.mp3");
                    try (FileOutputStream file = new FileOutputStream(output); java.io.InputStream input = connection.getInputStream()) {
                        byte[] buffer = new byte[8192]; int count;
                        while ((count = input.read(buffer)) != -1) file.write(buffer, 0, count);
                    }
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        if (play(output)) {
                            if (callback != null) callback.onPlayed();
                        } else if (callback != null) callback.onError();
                    });
                } finally {
                    connection.disconnect();
                }
            } catch (Exception ignored) { notifySpeechError(callback); }
        }).start();
    }

    private String transcribeWithFallback(File audio) throws Exception {
        Exception gapError = null;
        if (gapKey() != null) {
            try { return transcribeGapGpt(audio, "whisper-1"); }
            catch (Exception first) {
                gapError = first;
            }
        }
        if (liaraKey() != null) return transcribeLiara(audio);
        if (gapError != null) throw gapError;
        throw new IllegalStateException("No online speech provider key");
    }

    private String transcribeGapGpt(File audio, String model) throws Exception {
        String apiKey = gapKey();
        if (apiKey == null) throw new IllegalStateException("No GapGPT key");
        String boundary = "----DriveMate" + UUID.randomUUID();
        HttpURLConnection connection = open("https://api.gapgpt.app/v1/audio/transcriptions", apiKey);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (OutputStream out = connection.getOutputStream()) {
            writeField(out, boundary, "model", model);
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"voice.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            try (FileInputStream input = new FileInputStream(audio)) { byte[] buffer = new byte[8192]; int count; while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count); }
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        try {
            int code = connection.getResponseCode();
            String response = readResponse(connection, code);
            if (code >= 300) throw new IllegalStateException("GapGPT HTTP " + code);
            String text = new JSONObject(response).optString("text").trim();
            if (text.isEmpty()) throw new IllegalStateException("GapGPT پاسخ خالی داد");
            return text;
        } finally { connection.disconnect(); }
    }

    private String transcribeLiara(File audio) throws Exception {
        byte[] bytes;
        try (FileInputStream input = new FileInputStream(audio)) {
            bytes = new byte[(int) audio.length()];
            int offset = 0, count;
            while (offset < bytes.length && (count = input.read(bytes, offset, bytes.length - offset)) != -1) offset += count;
        }
        String encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
        JSONObject audioInput = new JSONObject().put("type", "input_audio")
                .put("input_audio", new JSONObject().put("data", encoded).put("format", "mp3"));
        org.json.JSONArray content = new org.json.JSONArray()
                .put(new JSONObject().put("type", "text").put("text", "این صدای فارسی را فقط به متن دقیق تبدیل کن."))
                .put(audioInput);
        JSONObject body = new JSONObject().put("model", "google/gemini-2.0-flash-001")
                .put("messages", new org.json.JSONArray().put(new JSONObject().put("role", "user").put("content", content)));
        HttpURLConnection connection = open(liaraBaseUrl() + "/chat/completions", liaraKey());
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        try (OutputStream out = connection.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        try {
            int code = connection.getResponseCode();
            String response = readResponse(connection, code);
            if (code >= 300) throw new IllegalStateException("Liara HTTP " + code);
            String text = new JSONObject(response).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content").trim();
            if (text.isEmpty()) throw new IllegalStateException("لیارا پاسخ خالی داد");
            return text;
        } finally { connection.disconnect(); }
    }

    private void writeField(OutputStream out, String boundary, String name, String value) throws Exception {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private HttpURLConnection open(String endpoint, String apiKey) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(10000); connection.setReadTimeout(20000); connection.setRequestMethod("POST");
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Accept", "application/json");
        return connection;
    }

    private String gapKey() {
        String value = keys.get("GAPGPT_API_KEY");
        if (value == null || value.trim().isEmpty()) value = keys.get("AI_API_KEY");
        if (value == null || value.trim().isEmpty()) value = buildKey;
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private String liaraKey() {
        String value = keys.get("LIARA_API_KEY");
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private String liaraBaseUrl() {
        String baseUrl = keys.get("LIARA_BASE_URL");
        if (baseUrl == null || baseUrl.trim().isEmpty()) baseUrl = "https://ai.liara.ir/api/69467b6ba99a2016cac892e1/v1";
        return baseUrl.replaceAll("/+$", "");
    }

    private boolean play(File file) {
        if (player != null) { player.release(); player = null; }
        player = new MediaPlayer();
        try { player.setDataSource(file.getAbsolutePath()); player.prepare(); player.start(); return true; }
        catch (Exception ignored) { player.release(); player = null; return false; }
    }

    private void releaseRecorder() { if (recorder != null) { recorder.release(); recorder = null; } }

    public void cancelRecording() { releaseRecorder(); }

    private String readResponse(HttpURLConnection connection, int code) throws Exception {
        java.io.InputStream stream = code < 300 ? connection.getInputStream() : connection.getErrorStream();
        if (stream == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder(); String line;
            while ((line = reader.readLine()) != null) response.append(line);
            return response.toString();
        }
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? "خطای ارتباط با سرویس" : message;
    }

    private void notifySpeechError(SpeechCallback callback) {
        if (callback == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(callback::onError);
    }
}
