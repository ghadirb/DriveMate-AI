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
import java.util.ArrayDeque;

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
    private volatile String lastTtsProvider = "";
    private volatile String transcriptionHint = "";
    private final ArrayDeque<SpeechRequest> speechQueue = new ArrayDeque<>();
    private boolean speechSynthesisInFlight;
    /** Bumped by stopPlayback() so a speak() request still synthesizing on its background thread
     *  (the network round-trip can take a couple of seconds) can tell, once it returns, that it
     *  was superseded by a stop/mode-switch/newer announcement in the meantime and must not play
     *  its now-stale audio. Callers already call stopPlayback() before/after switching modes or
     *  stopping navigation, but that only stops audio that is *already playing* - a request whose
     *  network call hadn't finished yet at that moment previously had no way to know it should
     *  discard its result, so it kept playing anyway once the download completed. */
    private volatile int playGeneration;

    public OnlineSpeechClient(Context context, String buildKey) {
        this.context = context.getApplicationContext();
        this.buildKey = buildKey;
    }

    public void setRuntimeKeys(RuntimeKeys keys) { if (keys != null) this.keys = keys; }
    /** Optional Persian vocabulary that helps the recognizer preserve saved destination names. */
    public void setTranscriptionHint(String value) {
        String normalized = value == null ? "" : value.trim();
        transcriptionHint = normalized.length() > 900 ? normalized.substring(0, 900) : normalized;
    }
    public boolean canUseOnlineSpeech() { return gapKey() != null || liaraKey() != null; }
    /** TTS currently uses the documented GapGPT audio endpoint; Liara is STT fallback only. */
    public boolean canUseOnlineTts() { return gapKey() != null; }
    public String getLastTtsProvider() { return lastTtsProvider; }

    public boolean startRecording() {
        try {
            recording = new File(context.getCacheDir(), "voice-command.m4a");
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION);
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

    /** Serializes online TTS requests so successive navigation messages cannot cut each other off. */
    public synchronized void speak(String text, SpeechCallback callback) {
        if (gapKey() == null || text == null || text.trim().isEmpty()) {
            if (callback != null) callback.onError();
            return;
        }
        speechQueue.addLast(new SpeechRequest(text, callback));
        drainSpeechQueue();
    }

    private synchronized void drainSpeechQueue() {
        if (speechSynthesisInFlight || speechQueue.isEmpty()) return;
        final SpeechRequest request = speechQueue.peekFirst();
        final int requestGeneration = playGeneration;
        speechSynthesisInFlight = true;
        new Thread(() -> {
            try {
                File output;
                String key = gapKey();
                try {
                    output = synthesizeOpenAiTts(request.text, key, "gpt-4o-mini-tts", "answer-gpt4o-mini-tts.mp3");
                    lastTtsProvider = "GapGPT gpt-4o-mini-tts";
                } catch (Exception gptError) {
                    try {
                        output = synthesizeGeminiTts(request.text, key, true);
                        lastTtsProvider = "Gemini TTS";
                    } catch (Exception documentedError) {
                        try {
                            output = synthesizeGeminiTts(request.text, key, false);
                            lastTtsProvider = "Gemini TTS";
                        } catch (Exception compatibleError) {
                            output = synthesizeTts1(request.text, key);
                            lastTtsProvider = "GapGPT tts-1";
                        }
                    }
                }
                final File playableOutput = output;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    synchronized (OnlineSpeechClient.this) {
                        if (speechQueue.peekFirst() != request) return;
                        if (requestGeneration != playGeneration) {
                            speechQueue.removeFirst();
                            speechSynthesisInFlight = false;
                            if (request.callback != null) request.callback.onError();
                            drainSpeechQueue();
                            return;
                        }
                        speechQueue.removeFirst();
                        boolean played = play(playableOutput);
                        speechSynthesisInFlight = false;
                        if (request.callback != null) {
                            if (played) request.callback.onPlayed(); else request.callback.onError();
                        }
                        if (!played) drainSpeechQueue();
                    }
                });
            } catch (Exception error) {
                Log.e(TAG, "All online TTS providers failed", error);
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    synchronized (OnlineSpeechClient.this) {
                        if (speechQueue.peekFirst() != request) return;
                        speechQueue.removeFirst();
                        speechSynthesisInFlight = false;
                        if (request.callback != null) request.callback.onError();
                        drainSpeechQueue();
                    }
                });
            }
        }).start();
    }

    private File synthesizeGeminiTts(String text, String key, boolean documentedPayload) throws Exception {
        JSONObject voiceConfig;
        JSONObject speechConfig;
        JSONObject generationConfig;
        JSONObject body = new JSONObject();
        if (documentedPayload) {
            voiceConfig = new JSONObject().put("prebuilt_voice_config", new JSONObject().put("voice_name", "Kore"));
            speechConfig = new JSONObject().put("voice_config", voiceConfig);
            generationConfig = new JSONObject().put("response_modalities", new org.json.JSONArray().put("AUDIO"))
                    .put("speech_config", speechConfig);
            body.put("contents", "متن را با لحن طبیعی فارسی بخوان: " + text);
        } else {
            voiceConfig = new JSONObject().put("prebuiltVoiceConfig", new JSONObject().put("voiceName", "Kore"));
            speechConfig = new JSONObject().put("voiceConfig", voiceConfig);
            generationConfig = new JSONObject().put("responseModalities", new org.json.JSONArray().put("AUDIO"))
                    .put("speechConfig", speechConfig);
            body.put("contents", new org.json.JSONArray().put(new JSONObject().put("parts",
                    new org.json.JSONArray().put(new JSONObject().put("text", "متن را با لحن طبیعی فارسی بخوان: " + text)))));
        }
        body.put("generationConfig", generationConfig);
        HttpURLConnection connection = open("https://api.gapgpt.app/v1/models/gemini-2.5-pro-preview-tts:generateContent", key);
        try {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = connection.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int code = connection.getResponseCode();
            String response = readResponse(connection, code);
            if (code >= 300) throw new IllegalStateException("Gemini TTS HTTP " + code + ": " + compactError(response));
            String encoded = extractGeminiAudio(response);
            if (encoded.isEmpty()) throw new IllegalStateException("Gemini TTS response had no audio data");
            byte[] pcm = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT);
            if (pcm.length < 64) throw new IllegalStateException("Gemini TTS audio was empty");
            File output = new File(context.getCacheDir(), "answer-gemini.wav");
            writePcmWav(output, pcm, 24_000);
            return output;
        } finally {
            connection.disconnect();
        }
    }

    private File synthesizeTts1(String text, String key) throws Exception {
        return synthesizeOpenAiTts(text, key, "tts-1", "answer-tts1.mp3");
    }

    /** Implements the documented OpenAI-compatible GapGPT audio/speech request. */
    private File synthesizeOpenAiTts(String text, String key, String model, String filename) throws Exception {
        JSONObject body = new JSONObject().put("model", model).put("voice", "alloy").put("input", text);
        HttpURLConnection connection = open("https://api.gapgpt.app/v1/audio/speech", key);
        try {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = connection.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int code = connection.getResponseCode();
            if (code >= 300) throw new IllegalStateException(model + " HTTP " + code + ": " + compactError(readResponse(connection, code)));
            File output = new File(context.getCacheDir(), filename);
            try (FileOutputStream file = new FileOutputStream(output); java.io.InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) file.write(buffer, 0, count);
            }
            if (output.length() < 64) throw new IllegalStateException(model + " audio was empty");
            return output;
        } finally {
            connection.disconnect();
        }
    }

    private String extractGeminiAudio(String response) throws Exception {
        JSONObject root = new JSONObject(response);
        org.json.JSONArray candidates = root.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) return "";
        JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
        if (content == null) return "";
        org.json.JSONArray parts = content.optJSONArray("parts");
        if (parts == null) return "";
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.getJSONObject(i);
            JSONObject inline = part.optJSONObject("inlineData");
            if (inline == null) inline = part.optJSONObject("inline_data");
            if (inline != null) {
                String data = inline.optString("data");
                if (!data.trim().isEmpty()) return data;
            }
        }
        return "";
    }

    private void writePcmWav(File output, byte[] pcm, int sampleRate) throws Exception {
        int channels = 1;
        int bitsPerSample = 16;
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        byte[] header = new byte[44];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, header, 0, 4);
        putLeInt(header, 4, 36 + pcm.length);
        System.arraycopy("WAVEfmt ".getBytes(StandardCharsets.US_ASCII), 0, header, 8, 8);
        putLeInt(header, 16, 16);
        putLeShort(header, 20, 1);
        putLeShort(header, 22, channels);
        putLeInt(header, 24, sampleRate);
        putLeInt(header, 28, byteRate);
        putLeShort(header, 32, channels * bitsPerSample / 8);
        putLeShort(header, 34, bitsPerSample);
        System.arraycopy("data".getBytes(StandardCharsets.US_ASCII), 0, header, 36, 4);
        putLeInt(header, 40, pcm.length);
        try (FileOutputStream stream = new FileOutputStream(output)) {
            stream.write(header);
            stream.write(pcm);
        }
    }

    private void putLeInt(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
    }

    private void putLeShort(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
    }

    private String transcribeWithFallback(File audio) throws Exception {
        Exception gapError = null;
        if (gapKey() != null) {
            try { return transcribeGapGpt(audio, "whisper-1", true); }
            catch (Exception first) {
                Log.w(TAG, "GapGPT STT hint request failed; retrying documented basic request", first);
                try { return transcribeGapGpt(audio, "whisper-1", false); }
                catch (Exception second) { gapError = second; }
            }
        }
        if (liaraKey() != null) return transcribeLiara(audio);
        if (gapError != null) throw gapError;
        throw new IllegalStateException("No online speech provider key");
    }

    private String transcribeGapGpt(File audio, String model, boolean includeRecognitionHints) throws Exception {
        String apiKey = gapKey();
        if (apiKey == null) throw new IllegalStateException("No GapGPT key");
        String boundary = "----DriveMate" + UUID.randomUUID();
        HttpURLConnection connection = open("https://api.gapgpt.app/v1/audio/transcriptions", apiKey);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (OutputStream out = connection.getOutputStream()) {
            writeField(out, boundary, "model", model);
            if (includeRecognitionHints) {
                writeField(out, boundary, "language", "fa");
                if (!transcriptionHint.isEmpty()) writeField(out, boundary, "prompt", transcriptionHint);
            }
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
            Log.i(TAG, "STT provider=GapGPT model=" + model + " hints=" + includeRecognitionHints
                    + " textLength=" + text.length());
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
        player = new MediaPlayer();
        try {
            player.setDataSource(file.getAbsolutePath());
            player.prepare();
            player.setOnCompletionListener(completed -> {
                synchronized (OnlineSpeechClient.this) {
                    try { completed.release(); } catch (Exception ignored) { }
                    if (player == completed) player = null;
                    drainSpeechQueue();
                }
            });
            player.setOnErrorListener((mp, what, extra) -> {
                synchronized (OnlineSpeechClient.this) {
                    try { mp.release(); } catch (Exception ignored) { }
                    if (player == mp) player = null;
                    speechSynthesisInFlight = false;
                    drainSpeechQueue();
                }
                return true;
            });
            player.start();
            return true;
        } catch (Exception ignored) {
            try { player.release(); } catch (Exception ignoredToo) { }
            player = null;
            return false;
        }
    }

    /** Must be called before a more important local alert so online speech cannot overlap it.
     *  Also invalidates any speak() request still synthesizing in the background so it won't
     *  start playing once it comes back (see playGeneration). */
    public synchronized void stopPlayback() {
        playGeneration++;
        speechQueue.clear();
        speechSynthesisInFlight = false;
        if (player != null) {
            try { player.stop(); } catch (IllegalStateException ignored) { }
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
    }

    private static final class SpeechRequest {
        final String text;
        final SpeechCallback callback;
        SpeechRequest(String text, SpeechCallback callback) {
            this.text = text;
            this.callback = callback;
        }
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

    private String compactError(String response) {
        if (response == null || response.trim().isEmpty()) return "empty response";
        String compact = response.replaceAll("\\s+", " ").trim();
        return compact.length() > 180 ? compact.substring(0, 180) : compact;
    }

    private void notifySpeechError(SpeechCallback callback) {
        if (callback == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(callback::onError);
    }
}
