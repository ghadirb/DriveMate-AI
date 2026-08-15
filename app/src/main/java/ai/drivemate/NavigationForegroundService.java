package ai.drivemate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps the navigation session visible while a trip is active. MainActivity remains the
 * authoritative GPS/navigation/voice owner in the current architecture; this service is the
 * Android foreground-service anchor that keeps that process eligible to continue in background.
 */
public class NavigationForegroundService extends Service {
    public static final String ACTION_STOP = "ai.drivemate.action.STOP_NAVIGATION";
    public static final String ACTION_STOP_BROADCAST = "ai.drivemate.action.STOP_NAVIGATION_BROADCAST";
    // v2 intentionally avoids inheriting an old user/channel choice made for the previous LOW
    // channel. Navigation is an ongoing user-visible task and must remain discoverable in the
    // notification drawer; the user can still change the channel's behavior in system settings.
    private static final String CHANNEL_ID = "navigation_active_v2";
    private static final int NOTIFICATION_ID = 410;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            sendBroadcast(new Intent(ACTION_STOP_BROADCAST).setPackage(getPackageName()));
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        createChannel();
        // Must happen immediately after startForegroundService() on Android O+.
        startForeground(NOTIFICATION_ID, buildNotification());
        return START_STICKY;
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class)
                .setAction(MainActivity.ACTION_VOICE_FROM_NOTIFICATION)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 1, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, NavigationForegroundService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 2, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("همراه راننده فعال است")
                .setContentText("مسیریابی و راهنمای صوتی در پس‌زمینه ادامه دارد")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_NAVIGATION)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(null, "مقصد را بگویید", open).build())
                .addAction(new Notification.Action.Builder(null, "توقف", stop).build());
        if (Build.VERSION.SDK_INT >= 21) builder.setShowWhen(false);
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return builder.build();
    }

    private void createChannel() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel existing = manager.getNotificationChannel(CHANNEL_ID);
        if (existing == null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "مسیریابی فعال", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("اعلان دائمی برای ادامه مسیریابی و راهنمای صوتی در پس‌زمینه");
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        // The user can stop navigation explicitly from the notification. Removing the launcher
        // task alone must not terminate the active foreground navigation session.
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        // Do not broadcast a stop here: START_STICKY allows Android to recreate the foreground
        // anchor after resource pressure while the authoritative Activity/session is still alive.
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
