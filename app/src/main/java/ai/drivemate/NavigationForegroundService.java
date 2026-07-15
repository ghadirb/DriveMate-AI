package ai.drivemate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

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
        startForeground(NOTIFICATION_ID, buildNotification());
        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class)
                .setAction(MainActivity.ACTION_VOICE_FROM_NOTIFICATION)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent voice = PendingIntent.getActivity(this, 1, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, NavigationForegroundService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 2, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("همراه راننده فعال است")
                .setContentText("راهنمایی مسیر در پس‌زمینه ادامه دارد")
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "مقصد را بگویید", voice).build())
                .addAction(new Notification.Action.Builder(null, "توقف", stop).build())
                .build();
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "مسیریابی فعال", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("کنترل و ادامه مسیریابی در پس‌زمینه");
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
