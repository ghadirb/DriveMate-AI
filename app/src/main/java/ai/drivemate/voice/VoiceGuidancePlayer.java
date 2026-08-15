package ai.drivemate.voice;

import android.content.Context;
import android.media.MediaPlayer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Serial, non-overlapping local guidance player with safety priority and short-window dedupe. */
public class VoiceGuidancePlayer {
    private static final String TAG = "DriveMateVoice";
    private static final long DUPLICATE_WINDOW_MS = 20_000L;
    private static final Set<String> REAL_CLIPS = new HashSet<>(Arrays.asList(
            "alternative_route", "continue_route", "dangerous_curve_ahead", "danger_ahead", "delay_10_min",
            "destination_arrived", "fuel_station_1km", "fuel_station_5km", "heavy_traffic", "low_fuel_warning",
            "make_u_turn", "parking_nearby", "reduce_speed", "roundabout_exit_1", "roundabout_exit_2",
            "roundabout_exit_3", "soon_turn_left", "soon_turn_right", "speeding_danger", "speed_bump_warning",
            "speed_camera", "speed_limit_110", "speed_limit_120", "speed_limit_30", "speed_limit_60",
            "speed_limit_80", "speed_limit_90", "speed_limit_attention", "start_navigation", "stop_ahead",
            "sudden_stop_warning", "turn_left", "turn_left_100m", "turn_left_200m", "turn_left_500m",
            "turn_right", "turn_right_100m", "turn_right_200m", "turn_right_300m", "turn_right_500m",
            "u_turn_100m", "u_turn_300m"
    ));
    private static final Set<String> SAFETY_CLIPS = new HashSet<>(Arrays.asList(
            "dangerous_curve_ahead", "danger_ahead", "speeding_danger", "speed_bump_warning",
            "speed_camera", "sudden_stop_warning", "stop_ahead", "reduce_speed"
    ));

    private final Context context;
    private final ArrayDeque<Item> queue = new ArrayDeque<>();
    private float volume = 0.85f;
    private MediaPlayer player;
    private TextToSpeech textToSpeech;
    private boolean ttsReady;
    private boolean ttsAvailable = true;
    private boolean playing;
    private String lastGuidanceKey;
    private long lastGuidanceAt;

    public VoiceGuidancePlayer(Context context) {
        this.context = context.getApplicationContext();
        textToSpeech = new TextToSpeech(this.context, status -> {
            synchronized (VoiceGuidancePlayer.this) {
                if (status != TextToSpeech.SUCCESS) {
                    ttsAvailable = false;
                    Log.w(TAG, "TextToSpeech engine could not be initialised on this device.");
                    drain();
                    return;
                }
                int result = textToSpeech.setLanguage(new Locale("fa", "IR"));
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    textToSpeech.setLanguage(Locale.getDefault());
                    Log.w(TAG, "Persian TTS voice is unavailable; using the device default voice.");
                }
                textToSpeech.setSpeechRate(1.0f);
                ttsReady = true;
                drain();
            }
        });
    }

    public synchronized boolean announce(String clipName, String fallbackText) {
        String resolved = resolveClipName(clipName);
        int resId = context.getResources().getIdentifier(resolved, "raw", context.getPackageName());
        boolean hasClip = REAL_CLIPS.contains(resolved) && resId != 0;
        String key = hasClip ? "clip:" + resolved : "text:" + normalize(fallbackText);
        if (isRecentDuplicate(key)) return false;

        Item item;
        if (hasClip) item = Item.clip(resId, resolved, isSafetyClip(resolved));
        else if (fallbackText != null && !fallbackText.trim().isEmpty()) item = Item.text(fallbackText, false);
        else return false;

        if (item.priority) {
            // Safety guidance must not wait behind ordinary navigation or AI speech.
            removeNonSafetyQueuedItems();
            if (playing) {
                stopMediaOnly();
                if (textToSpeech != null) textToSpeech.stop();
                playing = false;
            }
            queue.addFirst(item);
        } else {
            queue.addLast(item);
        }
        remember(key);
        drain();
        return true;
    }

    public synchronized void play(String clipName) {
        String resolved = resolveClipName(clipName);
        int resId = context.getResources().getIdentifier(resolved, "raw", context.getPackageName());
        if (!REAL_CLIPS.contains(resolved) || resId == 0) return;
        String key = "clip:" + resolved;
        if (isRecentDuplicate(key)) return;
        Item item = Item.clip(resId, resolved, isSafetyClip(resolved));
        if (item.priority) {
            removeNonSafetyQueuedItems();
            if (playing) {
                stopMediaOnly();
                if (textToSpeech != null) textToSpeech.stop();
                playing = false;
            }
            queue.addFirst(item);
        } else queue.addLast(item);
        remember(key);
        drain();
    }

    public synchronized boolean speak(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String normalized = normalize(text);
        if (normalized.isEmpty()) return false;
        String key = "text:" + normalized;
        if (isRecentDuplicate(key) || hasSimilarRecentText(normalized)) return false;
        queue.addLast(Item.text(text, false));
        remember(key);
        drain();
        return true;
    }

    private String resolveClipName(String name) {
        if ("arrived".equals(name)) return "destination_arrived";
        if ("route_recalculated".equals(name)) return "alternative_route";
        if ("u_turn".equals(name)) return "make_u_turn";
        return name;
    }

    private synchronized void drain() {
        if (playing) return;
        while (!queue.isEmpty()) {
            Item item = queue.peekFirst();
            if (item.clipResId != 0) {
                queue.removeFirst();
                if (startClip(item.clipResId, item.label)) return;
                continue;
            }
            if (!ttsAvailable) { queue.removeFirst(); continue; }
            if (!ttsReady || textToSpeech == null) return;
            queue.removeFirst();
            if (startTts(item.text)) return;
        }
    }

    private boolean startClip(int resId, String label) {
        stopMediaOnly();
        if (textToSpeech != null) textToSpeech.stop();
        player = MediaPlayer.create(context, resId);
        if (player == null) return false;
        player.setVolume(volume, volume);
        playing = true;
        player.setOnCompletionListener(completed -> {
            completed.release();
            synchronized (VoiceGuidancePlayer.this) {
                if (player == completed) player = null;
                playing = false;
                drain();
            }
        });
        player.setOnErrorListener((mp, what, extra) -> {
            try { mp.reset(); } catch (Exception ignored) { }
            mp.release();
            synchronized (VoiceGuidancePlayer.this) {
                if (player == mp) player = null;
                playing = false;
                drain();
            }
            return true;
        });
        try {
            player.start();
        } catch (RuntimeException e) {
            try { player.release(); } catch (Exception ignored) { }
            player = null;
            playing = false;
            return false;
        }
        return true;
    }

    private boolean startTts(String text) {
        if (textToSpeech == null || !ttsReady) return false;
        playing = true;
        final String utteranceId = "drivemate_tts_" + System.nanoTime();
        textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { finishTts(); }
            @Override public void onError(String id) { finishTts(); }
        });
        int result = textToSpeech.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId);
        if (result != TextToSpeech.SUCCESS) {
            playing = false;
            return false;
        }
        return true;
    }

    private void finishTts() {
        synchronized (this) {
            playing = false;
            drain();
        }
    }

    private boolean isSafetyClip(String clip) { return SAFETY_CLIPS.contains(clip); }

    private void removeNonSafetyQueuedItems() {
        if (queue.isEmpty()) return;
        ArrayDeque<Item> kept = new ArrayDeque<>();
        for (Item item : queue) if (item.priority) kept.addLast(item);
        queue.clear();
        queue.addAll(kept);
    }

    private boolean isRecentDuplicate(String key) {
        return key != null && key.equals(lastGuidanceKey)
                && System.currentTimeMillis() - lastGuidanceAt <= DUPLICATE_WINDOW_MS;
    }

    private void remember(String key) {
        lastGuidanceKey = key;
        lastGuidanceAt = System.currentTimeMillis();
    }

    private boolean hasSimilarRecentText(String normalized) {
        if (lastGuidanceKey == null || !lastGuidanceKey.startsWith("text:")
                || System.currentTimeMillis() - lastGuidanceAt > DUPLICATE_WINDOW_MS) return false;
        String previous = lastGuidanceKey.substring(5);
        if (previous.equals(normalized)) return true;
        String[] a = previous.split(" ");
        String[] b = normalized.split(" ");
        if (a.length < 3 || b.length < 3) return false;
        int common = 0;
        for (String x : a) for (String y : b) if (x.equals(y) && x.length() >= 3) { common++; break; }
        return common >= Math.max(3, Math.min(a.length, b.length) * 2 / 3);
    }

    private String normalize(String text) {
        if (text == null) return "";
        return text.replace('\u200c', ' ').replace('\u064a', '\u06cc').replace('\u0643', '\u06a9')
                .replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    public synchronized void increaseVolume() { volume = Math.min(1f, volume + 0.15f); }
    public synchronized void decreaseVolume() { volume = Math.max(0.2f, volume - 0.15f); }

    private void stopMediaOnly() {
        if (player != null) {
            try { player.stop(); } catch (IllegalStateException ignored) { }
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
    }

    public synchronized void interrupt() {
        queue.clear();
        stopMediaOnly();
        if (textToSpeech != null) textToSpeech.stop();
        playing = false;
    }

    public synchronized void shutdown() {
        queue.clear();
        stopMediaOnly();
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
        }
        playing = false;
    }

    private static final class Item {
        final int clipResId;
        final String label;
        final String text;
        final boolean priority;
        private Item(int clipResId, String label, String text, boolean priority) {
            this.clipResId = clipResId;
            this.label = label;
            this.text = text;
            this.priority = priority;
        }
        static Item clip(int resId, String label, boolean priority) { return new Item(resId, label, null, priority); }
        static Item text(String text, boolean priority) { return new Item(0, null, text, priority); }
    }
}
