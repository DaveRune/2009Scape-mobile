package net.kdt.pojavlaunch.services;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.MainActivity;
import net.kdt.pojavlaunch.R;

/**
 * Holds the game process above the cached state so Android does not freeze it while the app is in the background.
 * Android only exempts a process from freezing while it runs a foreground service, and a foreground service must show
 * an ongoing notification.
 */
public class GameService extends Service {
    private static final String CHANNEL_ID = "game_channel";
    private static final int NOTIFICATION_ID = 2;

    public static void startService(Context context) {
        ContextCompat.startForegroundService(context, new Intent(context, GameService.class));
    }

    public static void stopService(Context context) {
        context.stopService(new Intent(context, GameService.class));
    }

    @Override
    public void onCreate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.notif_channel_game_name), NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            NotificationManagerCompat.from(this).createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Intent returnIntent = new Intent(this, MainActivity.class);
        returnIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent returnPendingIntent = PendingIntent.getActivity(this, 0, returnIntent,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

        startForeground(NOTIFICATION_ID, new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_short_name))
                .setContentText(getString(R.string.notif_game_running))
                .setSmallIcon(R.drawable.notif_icon_game)
                .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher))
                .setContentIntent(returnPendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setShowWhen(false)
                .build());

        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
