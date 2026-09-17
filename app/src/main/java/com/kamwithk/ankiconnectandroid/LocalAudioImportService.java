package com.kamwithk.ankiconnectandroid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;
import com.kamwithk.ankiconnectandroid.routing.database.LocalAudioImporter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service that imports a user-selected {@code android.db} so a multi-gigabyte
 * copy is not killed while the app is in the background.
 */
public class LocalAudioImportService extends Service {
    private static final String TAG = "AnkiconnectAndroid";

    public static final String ACTION_IMPORT = "com.kamwithk.ankiconnectandroid.action.IMPORT_LOCAL_AUDIO";
    public static final String ACTION_CANCEL = "com.kamwithk.ankiconnectandroid.action.CANCEL_LOCAL_AUDIO_IMPORT";
    public static final String EXTRA_URI = "uri";

    /** Preference key holding the last import result, surfaced in the settings screen. */
    public static final String PREF_LAST_RESULT = "local_audio_last_import_result";

    private static final String CHANNEL_ID = "localAudioImport";
    private static final int NOTIFICATION_ID = 2;

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private NotificationManager notificationManager;
    private Thread worker;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = getSystemService(NotificationManager.class);
        NotificationChannel channel =
                new NotificationChannel(CHANNEL_ID, "Local audio import", NotificationManager.IMPORTANCE_LOW);
        notificationManager.createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_CANCEL.equals(intent.getAction())) {
            cancelled.set(true);
            return START_NOT_STICKY;
        }

        Uri uri = intent.getData();
        if (uri == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildProgressNotification("Importing local audio database…", 0, -1));

        if (worker != null && worker.isAlive()) {
            return START_NOT_STICKY;
        }

        worker = new Thread(() -> runImport(uri), "local-audio-import");
        worker.start();
        return START_NOT_STICKY;
    }

    private void runImport(Uri uri) {
        LocalAudioImporter.Result result = LocalAudioImporter.importFromUri(this, uri, this::updateProgress, cancelled);

        PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putString(PREF_LAST_RESULT, result.message)
                .apply();

        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, buildResultNotification(result.message));
        }
        stopForeground(false);
        stopSelf();
    }

    private void updateProgress(long copied, long total) {
        if (notificationManager == null) {
            return;
        }
        String text = total > 0
                ? String.format(
                        java.util.Locale.US,
                        "Importing local audio database… %d%%",
                        Math.min(100, copied * 100 / total))
                : "Importing local audio database…";
        notificationManager.notify(NOTIFICATION_ID, buildProgressNotification(text, copied, total));
    }

    private Notification buildProgressNotification(String text, long copied, long total) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Ankiconnect Android")
                .setContentText(text)
                .setSmallIcon(R.mipmap.app_launcher)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(0, "Cancel", cancelPendingIntent());

        if (total > 0) {
            builder.setProgress(100, (int) Math.min(100, copied * 100 / total), false);
        } else {
            builder.setProgress(0, 0, true);
        }
        return builder.build();
    }

    private Notification buildResultNotification(String message) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Ankiconnect Android")
                .setContentText(message)
                .setSmallIcon(R.mipmap.app_launcher)
                .setAutoCancel(true)
                .build();
    }

    private PendingIntent cancelPendingIntent() {
        Intent intent = new Intent(this, LocalAudioImportService.class).setAction(ACTION_CANCEL);
        return PendingIntent.getService(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public void onDestroy() {
        if (worker != null) {
            worker.interrupt();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Intent used to start an import from an activity. */
    public static Intent importIntent(android.content.Context context, Uri uri) {
        Intent intent = new Intent(context, LocalAudioImportService.class);
        intent.setAction(ACTION_IMPORT);
        intent.setData(uri);
        return intent;
    }
}
