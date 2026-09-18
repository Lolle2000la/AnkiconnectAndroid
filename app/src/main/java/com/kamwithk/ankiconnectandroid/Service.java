package com.kamwithk.ankiconnectandroid;

import static com.kamwithk.ankiconnectandroid.MainActivity.CHANNEL_ID;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import com.kamwithk.ankiconnectandroid.routing.Router;
import com.kamwithk.ankiconnectandroid.routing.database.LocalAudioDatabase;
import java.io.IOException;

public class Service extends android.app.Service {
    public static final int PORT = 8765;
    public static final String ACTION_STOP = "com.kamwithk.ankiconnectandroid.action.STOP";
    private static final String TAG = "AnkiconnectAndroid";
    private static final int NOTIFICATION_ID = 1;
    private static final String PREF_PAUSE_SERVER_WHEN_SCREEN_OFF = "pause_server_when_screen_off";
    private static final String PREF_ALLOW_LAN_ACCESS = "allow_lan_access";

    private Router server;
    private boolean pausedForScreenOff = false;
    private boolean screenStateReceiverRegistered = false;

    private final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            applyScreenState();
            updateNotification(
                    pausedForScreenOff ? R.string.server_notification_paused : R.string.service_status_running);
        }
    };

    /**
     * Reopens the listening socket so a change to the bind address (loopback vs all interfaces)
     * takes effect without the user having to stop and start the server manually.
     */
    private final SharedPreferences.OnSharedPreferenceChangeListener bindPreferenceListener = (preferences, key) -> {
        if (PREF_ALLOW_LAN_ACCESS.equals(key)) {
            Log.i(TAG, "Bind preference changed; reopening the server socket");
            if (server != null) {
                server.stop();
                server = null;
            }
            applyScreenState();
        }
    };

    @Override
    public void onCreate() { // Only one time
        super.onCreate();
        ServiceState.set(ServiceState.State.STARTING);

        IntentFilter screenStateFilter = new IntentFilter();
        screenStateFilter.addAction(Intent.ACTION_SCREEN_OFF);
        screenStateFilter.addAction(Intent.ACTION_SCREEN_ON);
        ContextCompat.registerReceiver(
                this, screenStateReceiver, screenStateFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
        screenStateReceiverRegistered = true;

        PreferenceManager.getDefaultSharedPreferences(this)
                .registerOnSharedPreferenceChangeListener(bindPreferenceListener);
    }

    private void startServer() {
        if (server != null) {
            return;
        }
        try {
            server = new Router(PORT, this);
        } catch (IOException e) {
            Log.w(TAG, "The server was unable to bind port " + PORT, e);
        }
    }

    /**
     * Closes the listening socket while the screen is off (when the user opted in) so the radio is
     * not kept busy by inbound connections, and reopens it once the screen is back on.
     */
    private void applyScreenState() {
        if (pauseServerWhenScreenOffEnabled() && !isScreenOn()) {
            if (server != null) {
                Log.i(TAG, "Screen off: closing the server socket to save battery");
                server.stop();
                server = null;
            }
            pausedForScreenOff = true;
        } else {
            pausedForScreenOff = false;
            startServer();
        }
    }

    private boolean pauseServerWhenScreenOffEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(this).getBoolean(PREF_PAUSE_SERVER_WHEN_SCREEN_OFF, false);
    }

    private boolean isScreenOn() {
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return powerManager == null || powerManager.isInteractive();
    }

    private Notification buildNotification(int contentTextRes) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingIntent = PendingIntent.getActivity(
                    this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        } else {
            pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        }

        Intent stopIntent = new Intent(this, Service.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(contentTextRes))
                .setSmallIcon(R.mipmap.app_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                // The service is re-started (and re-posts this notification) on every onStartCommand,
                // including START_STICKY relaunches; without this each one would alert again.
                .setOnlyAlertOnce(true)
                .addAction(R.drawable.ic_stop, getString(R.string.action_stop_service), stopPendingIntent)
                .build();
    }

    private void updateNotification(int contentTextRes) {
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(contentTextRes));
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { // Every time start is called
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Log.i(TAG, "Stop requested from the notification");
            ServiceState.set(ServiceState.State.STOPPING);
            stopSelf();
            return START_NOT_STICKY;
        }

        boolean paused = pauseServerWhenScreenOffEnabled() && !isScreenOn();
        startForeground(
                NOTIFICATION_ID,
                buildNotification(paused ? R.string.server_notification_paused : R.string.service_status_running));

        // onCreate normally binds the port, but retry here in case it was still in use then
        // (for example during an app update). Otherwise the UI would claim the server is running
        // while nothing is listening.
        applyScreenState();
        if (!pausedForScreenOff && server == null) {
            Log.e(TAG, "Could not bind port " + PORT + "; stopping the server");
            Toast.makeText(this, "Could not start the server: port " + PORT + " is in use", Toast.LENGTH_LONG)
                    .show();
            ServiceState.set(ServiceState.State.STOPPED);
            stopSelf();
            return START_NOT_STICKY;
        }

        ServiceState.set(ServiceState.State.RUNNING);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(bindPreferenceListener);
        if (screenStateReceiverRegistered) {
            unregisterReceiver(screenStateReceiver);
            screenStateReceiverRegistered = false;
        }
        if (server != null) {
            server.stop();
            server = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        LocalAudioDatabase.invalidate();
        ServiceState.set(ServiceState.State.STOPPED);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
