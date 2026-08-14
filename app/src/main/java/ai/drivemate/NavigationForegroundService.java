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
 * Keeps the navigation process in the foreground while a trip is active. The actual route/GPS
 * engine remains owned by MainActivity for now; this service is deliberately a small lifecycle
 * anchor and notification controller. It must therefore never stop itself merely because the
 * launcher task/activity was removed.
 */
public class NavigationForegroundService extends Service {
    public static final String ACTION_STOP = "ai.drivemate.action.STOP_NAVIGATION";
    public static final String ACTION_STOP_BROADCAST = "ai.drivemate.action.STOP_NAVIGATION_BROADCAST";
    private static final String CHANNEL_ID = "navigation_active";
    private static final int NOTIFICATION_ID = 410;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            sendBroadcast(new Intent(ACTION_STOP_BROADCAST).setPackage(getPackageName()));
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        createChannel();
        // Must be called immediately after startForegroundService() on Android O+.
        startForeground(NOTIFICATION_ID, buildNotification());
        // Navigation is a user-visible, ongoing foreground task. If Android removes the task or
        // briefly recreates the service, ask it to recreate the service instead of silently losing
        // the foreground anchor that protects the Activity-owned GPS/navigation session.
        return START_STICKY;
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class)
                .setAction(MainActivity.ACTION_VOICE_FROM_NOTIFICATION)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent voice = PendingIntent.getActivity(this, 1, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, NavigationForegroundService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 2, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("همراه راننده فعال است")
                .setContentText("مسیریابی و راهنمای صوتی در پس‌زمینه ادامه دارد")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_NAVIGATION)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(null, "مقصد را بگویید", voice).build())
                .addAction(new Notification.Action.Builder(null, "توقف", stop).build());
        if (Build.VERSION.SDK_INT >= 21) builder.setShowWhen(false);
        return builder.build();
    }

    private void createChannel() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel existing = manager.getNotificationChannel(CHANNEL_ID);
        if (existing == null) {
            // LOW keeps navigation from making a sound on every service refresh while still making
            // the ongoing notification visible. A channel's importance cannot be raised after it
            // has been created, so do not try to mutate an existing user choice.
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "مسیریابی فعال", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("کنترل و ادامه مسیریابی در پس‌زمینه");
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        // Do not stop the service when the driver swipes the app away from Recents. The foreground
        // navigation session is explicitly allowed to continue until the driver taps "توقف".
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        // Do not broadcast a stop here: Android may destroy/recreate a START_STICKY foreground
        // service for resource-management reasons while navigation is still active.
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
