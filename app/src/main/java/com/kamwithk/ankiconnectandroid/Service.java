package com.kamwithk.ankiconnectandroid;

import static com.kamwithk.ankiconnectandroid.MainActivity.CHANNEL_ID;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import com.kamwithk.ankiconnectandroid.routing.Router;
import com.kamwithk.ankiconnectandroid.routing.database.LocalAudioDatabase;
import java.io.IOException;

public class Service extends android.app.Service {
    public static final int PORT = 8765;
    private static final String TAG = "AnkiconnectAndroid";

    private Router server;

    @Override
    public void onCreate() { // Only one time
        super.onCreate();
        ServiceState.set(ServiceState.State.STARTING);
        startServer();
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

    @SuppressLint("UnspecifiedImmutableFlag")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { // Every time start is called
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            pendingIntent = PendingIntent.getActivity(
                    this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        } else {
            pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Ankiconnect Android")
                .setSmallIcon(R.mipmap.app_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();

        startForeground(1, notification);

        // onCreate normally binds the port, but retry here in case it was still in use then
        // (for example during an app update). Otherwise the UI would claim the server is running
        // while nothing is listening.
        startServer();
        if (server == null) {
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
        if (server != null) {
            server.stop();
        }
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
