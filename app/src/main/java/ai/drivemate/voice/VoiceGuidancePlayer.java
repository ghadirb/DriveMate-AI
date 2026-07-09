package ai.drivemate.voice;

import android.content.Context;
import android.media.MediaPlayer;

public class VoiceGuidancePlayer {
    private final Context context;
    private float volume = 0.85f;
    private MediaPlayer player;

    public VoiceGuidancePlayer(Context context) {
        this.context = context;
    }

    public void play(String clipName) {
        int resId = context.getResources().getIdentifier(clipName, "raw", context.getPackageName());
        if (resId == 0) return;
        stopCurrent();
        player = MediaPlayer.create(context, resId);
        if (player == null) return;
        player.setVolume(volume, volume);
        player.setOnCompletionListener(MediaPlayer::release);
        player.start();
    }

    public void increaseVolume() {
        volume = Math.min(1f, volume + 0.15f);
    }

    public void decreaseVolume() {
        volume = Math.max(0.2f, volume - 0.15f);
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
}
