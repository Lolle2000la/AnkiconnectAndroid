package com.kamwithk.ankiconnectandroid;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

/**
 * Optionally starts the AnkiConnect server after a reboot.
 *
 * <p>Users previously had to open the app and tap "Start Service" after every boot.
 * The {@code BOOT_COMPLETED} broadcast is one of the allowed exemptions to Android's
 * background foreground-service start restrictions.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !"com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }

        boolean startOnBoot = PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("start_on_boot", true);
        if (!startOnBoot) {
            return;
        }

        ContextCompat.startForegroundService(context, new Intent(context, Service.class));
    }
}
