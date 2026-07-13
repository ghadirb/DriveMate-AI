package ai.drivemate.ai;

import android.content.Context;
import android.media.MediaPlayer;
import android.media.MediaRecorder;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
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
    public interface TextCallback { void onResult(String text); void onError(); }

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
    public boolean canUseOnlineSpeech() { return key() != null; }

    public boolean startRecording() {
        try {
            recording = new File(context.getCacheDir(), "voice-command.m4a");
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(64000);
            recorder.setAudioSamplingRate(16000);
            recorder.setOutputFile(recording.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            return true;
        } catch (Exception error) {
            releaseRecorder();
            return false;
        }
    }

    public void stopAndTranscribe(TextCallback callback) {
        if (recorder == null || recording == null) { callback.onError(); return; }
        try { recorder.stop(); } catch (RuntimeException ignored) { }
        releaseRecorder();
        File audio = recording;
        new Thread(() -> {
            try { callback.onResult(transcribe(audio)); }
            catch (Exception error) { callback.onError(); }
        }).start();
    }

    public void speak(String text) {
        String key = key();
        if (key == null || text == null || text.trim().isEmpty()) return;
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject().put("model", "tts-1").put("voice", "alloy").put("input", text);
                HttpURLConnection connection = open("https://api.gapgpt.app/v1/audio/speech", key);
                connection.setDoOutput(true);
                try (OutputStream out = connection.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
                if (connection.getResponseCode() >= 300) return;
                File output = new File(context.getCacheDir(), "answer.mp3");
                try (FileOutputStream file = new FileOutputStream(output); java.io.InputStream input = connection.getInputStream()) {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = input.read(buffer)) != -1) file.write(buffer, 0, count);
                }
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> play(output));
            } catch (Exception ignored) { }
        }).start();
    }

    private String transcribe(File audio) throws Exception {
        String apiKey = key();
        if (apiKey == null) throw new IllegalStateException("No GapGPT key");
        String boundary = "----DriveMate" + UUID.randomUUID();
        HttpURLConnection connection = open("https://api.gapgpt.app/v1/audio/transcriptions", apiKey);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (OutputStream out = connection.getOutputStream()) {
            writeField(out, boundary, "model", "whisper-1");
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"voice.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            try (FileInputStream input = new FileInputStream(audio)) { byte[] buffer = new byte[8192]; int count; while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count); }
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getResponseCode() < 300 ? connection.getInputStream() : connection.getErrorStream(), StandardCharsets.UTF_8));
        StringBuilder response = new StringBuilder(); String line; while ((line = reader.readLine()) != null) response.append(line);
        if (connection.getResponseCode() >= 300) throw new IllegalStateException("STT unavailable");
        return new JSONObject(response.toString()).optString("text");
    }

    private void writeField(OutputStream out, String boundary, String name, String value) throws Exception {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private HttpURLConnection open(String endpoint, String apiKey) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(10000); connection.setReadTimeout(25000); connection.setRequestMethod("POST");
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Accept", "application/json");
        return connection;
    }

    private String key() {
        String value = keys.get("GAPGPT_API_KEY");
        if (value == null || value.trim().isEmpty()) value = keys.get("AI_API_KEY");
        if (value == null || value.trim().isEmpty()) value = buildKey;
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private void play(File file) {
        if (player != null) { player.release(); player = null; }
        player = new MediaPlayer();
        try { player.setDataSource(file.getAbsolutePath()); player.prepare(); player.start(); }
        catch (Exception ignored) { player.release(); player = null; }
    }

    private void releaseRecorder() { if (recorder != null) { recorder.release(); recorder = null; } }
}
