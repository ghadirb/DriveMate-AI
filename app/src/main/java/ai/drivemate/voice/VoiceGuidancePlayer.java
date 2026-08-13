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

/** Serial, non-overlapping local guidance player. */
public class VoiceGuidancePlayer {
    private static final String TAG = "DriveMateVoice";
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

    private final Context context;
    private final ArrayDeque<Item> queue = new ArrayDeque<>();
    private float volume = 0.85f;
    private MediaPlayer player;
    private TextToSpeech textToSpeech;
    private boolean ttsReady;
    private boolean ttsAvailable = true;
    private boolean playing;

    public VoiceGuidancePlayer(Context context) {
        this.context = context.getApplicationContext();
        textToSpeech = new TextToSpeech(this.context, status -> {
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
        });
    }

    public synchronized boolean announce(String clipName, String fallbackText) {
        String resolved = resolveClipName(clipName);
        int resId = context.getResources().getIdentifier(resolved, "raw", context.getPackageName());
        if (REAL_CLIPS.contains(resolved) && resId != 0) {
            queue.addLast(Item.clip(resId, resolved));
        } else if (fallbackText != null && !fallbackText.trim().isEmpty()) {
            queue.addLast(Item.text(fallbackText));
        } else return false;
        drain();
        return true;
    }

    public synchronized void play(String clipName) {
        String resolved = resolveClipName(clipName);
        int resId = context.getResources().getIdentifier(resolved, "raw", context.getPackageName());
        if (!REAL_CLIPS.contains(resolved) || resId == 0) return;
        queue.addLast(Item.clip(resId, resolved));
        drain();
    }

    public synchronized boolean speak(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        queue.addLast(Item.text(text));
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
        player.start();
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
        // The app owns the queue, so never flush a currently playing warning.
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
        private Item(int clipResId, String label, String text) {
            this.clipResId = clipResId;
            this.label = label;
            this.text = text;
        }
        static Item clip(int resId, String label) { return new Item(resId, label, null); }
        static Item text(String text) { return new Item(0, null, text); }
    }
}
