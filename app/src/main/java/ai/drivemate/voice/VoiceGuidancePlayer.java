package ai.drivemate.voice;

import android.content.Context;
import android.media.MediaPlayer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.Locale;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Plays pre-recorded guidance clips (res/raw/*.wav) when one exists for the requested name.
 * When no matching clip exists (or the requested text is fully dynamic, e.g. an AI answer or a
 * live status message), falls back to the device's built-in, offline, free TextToSpeech engine
 * so the driver still hears something instead of silence.
 */
public class VoiceGuidancePlayer {
    private static final String TAG = "DriveMateVoice";
    private static final String UTTERANCE_ID = "drivemate_tts";
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
    private float volume = 0.85f;
    private MediaPlayer player;
    private TextToSpeech textToSpeech;
    private boolean ttsReady;
    private boolean ttsAvailable = true;

    public VoiceGuidancePlayer(Context context) {
        this.context = context.getApplicationContext();
        textToSpeech = new TextToSpeech(this.context, status -> {
            if (status != TextToSpeech.SUCCESS) {
                ttsAvailable = false;
                Log.w(TAG, "TextToSpeech engine could not be initialised on this device.");
                return;
            }
            int result = textToSpeech.setLanguage(new Locale("fa", "IR"));
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // No Persian voice installed on this device; fall back to the default language
                // rather than silently failing, so we still say *something*.
                textToSpeech.setLanguage(Locale.getDefault());
            }
            textToSpeech.setSpeechRate(1.0f);
            ttsReady = true;
        });
    }

    /** Plays a fixed clip if it exists; otherwise speaks fallbackText through local TTS. */
    public void announce(String clipName, String fallbackText) {
        String resolvedName = resolveClipName(clipName);
        int resId = context.getResources().getIdentifier(resolvedName, "raw", context.getPackageName());
        if (REAL_CLIPS.contains(resolvedName) && resId != 0) {
            playClip(resId);
            return;
        }
        speak(fallbackText);
    }

    /** Kept for call sites that only have a clip name and no dynamic text to fall back on. */
    public void play(String clipName) {
        String resolvedName = resolveClipName(clipName);
        int resId = context.getResources().getIdentifier(resolvedName, "raw", context.getPackageName());
        if (!REAL_CLIPS.contains(resolvedName) || resId == 0) return;
        playClip(resId);
    }

    private String resolveClipName(String name) {
        if ("arrived".equals(name)) return "destination_arrived";
        if ("route_recalculated".equals(name)) return "alternative_route";
        if ("u_turn".equals(name)) return "make_u_turn";
        return name;
    }

    /** Speaks arbitrary, dynamic Persian text (AI answers, live status, warnings) via local TTS. */
    public void speak(String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (!ttsAvailable || !ttsReady || textToSpeech == null) {
            Log.w(TAG, "TTS not ready yet; dropped: " + text);
            return;
        }
        stopCurrent();
        textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) { }
            @Override public void onDone(String utteranceId) { }
            @Override public void onError(String utteranceId) { }
        });
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID);
    }

    public void increaseVolume() {
        volume = Math.min(1f, volume + 0.15f);
    }

    public void decreaseVolume() {
        volume = Math.max(0.2f, volume - 0.15f);
    }

    private void playClip(int resId) {
        stopCurrent();
        if (textToSpeech != null) textToSpeech.stop();
        player = MediaPlayer.create(context, resId);
        if (player == null) return;
        player.setVolume(volume, volume);
        player.setOnCompletionListener(MediaPlayer::release);
        player.start();
    }

    private void stopCurrent() {
        if (player != null) {
            try {
                player.stop();
                player.release();
            } catch (IllegalStateException ignored) {
            }
            player = null;
        }
    }

    /** Call from Activity#onDestroy to release the TTS engine. */
    public void shutdown() {
        stopCurrent();
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
        }
    }
}
