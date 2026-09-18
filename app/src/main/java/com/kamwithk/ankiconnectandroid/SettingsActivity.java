package com.kamwithk.ankiconnectandroid;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.format.Formatter;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import com.kamwithk.ankiconnectandroid.routing.ApiKey;
import com.kamwithk.ankiconnectandroid.routing.database.LocalAudioImporter;
import java.io.File;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings, new SettingsFragment())
                    .commit();
        }

        Toolbar settingsToolbar = findViewById(R.id.settingsToolbar);
        setSupportActionBar(settingsToolbar);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            // adds back button
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {

        private ActivityResultLauncher<String[]> importDatabaseLauncher;

        private final SharedPreferences.OnSharedPreferenceChangeListener importResultListener = (preferences, key) -> {
            if (LocalAudioImportService.PREF_LAST_RESULT.equals(key)) {
                updateLocalAudioStatus();
            }
        };

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            importDatabaseLauncher =
                    registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onDatabasePicked);
        }

        @Override
        public void onResume() {
            super.onResume();
            updateLocalAudioStatus();
            updateBatteryOptimizationSummary();
            Context context = getContext();
            if (context != null) {
                PreferenceManager.getDefaultSharedPreferences(context)
                        .registerOnSharedPreferenceChangeListener(importResultListener);
            }
        }

        @Override
        public void onPause() {
            Context context = getContext();
            if (context != null) {
                PreferenceManager.getDefaultSharedPreferences(context)
                        .unregisterOnSharedPreferenceChangeListener(importResultListener);
            }
            super.onPause();
        }

        private void onDatabasePicked(Uri uri) {
            if (uri == null) {
                return;
            }
            Context context = getContext();
            if (context == null) {
                return;
            }
            try {
                context.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // Not all providers offer persistable permissions; the current read grant still applies.
            }
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                Toast.makeText(context, R.string.settings_import_notifications_disabled, Toast.LENGTH_LONG)
                        .show();
            }
            ContextCompat.startForegroundService(context, LocalAudioImportService.importIntent(context, uri));
            Toast.makeText(context, R.string.settings_import_local_audio_started, Toast.LENGTH_LONG)
                    .show();
        }

        private void updateBatteryOptimizationSummary() {
            Preference preference = findPreference("disable_battery_optimization");
            Context context = getContext();
            if (preference == null || context == null) {
                return;
            }
            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            boolean exempt =
                    powerManager != null && powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
            preference.setSummary(
                    exempt
                            ? R.string.settings_battery_optimization_summary_disabled
                            : R.string.settings_battery_optimization_summary_enabled);
        }

        private void updateLocalAudioStatus() {
            Preference status = findPreference("local_audio_db_status");
            Context context = getContext();
            if (status == null || context == null) {
                return;
            }

            StringBuilder summary = new StringBuilder();
            File databaseFile = LocalAudioImporter.getDatabaseFile(context);
            if (databaseFile.isFile()) {
                summary.append(databaseFile.getAbsolutePath())
                        .append("\n")
                        .append(Formatter.formatShortFileSize(context, databaseFile.length()));
            } else {
                summary.append(getString(R.string.settings_local_audio_status_missing));
            }

            String lastResult = PreferenceManager.getDefaultSharedPreferences(context)
                    .getString(LocalAudioImportService.PREF_LAST_RESULT, null);
            if (lastResult != null) {
                summary.append("\n").append(lastResult);
            }
            status.setSummary(summary.toString());
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            Context contextForKey = getContext();
            if (contextForKey != null) {
                // Ensure a key exists before the preference summary is bound.
                ApiKey.getOrCreateApiKey(contextForKey);
            }
            setPreferencesFromResource(R.xml.root_preferences, rootKey);

            Preference preference = findPreference("access_overlay_perms");
            if (preference != null) {
                // custom handler of preference: open permissions screen
                preference.setOnPreferenceClickListener(p -> {
                    Intent permIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                    startActivity(permIntent);
                    return true;
                });
            }

            EditTextPreference corsHostPreference = findPreference("cors_hostname");
            if (corsHostPreference != null) {
                corsHostPreference.setOnBindEditTextListener(editText -> editText.setHint("e.g. http://example.com"));
            }

            preference = findPreference("disable_battery_optimization");
            if (preference != null) {
                preference.setOnPreferenceClickListener(p -> {
                    Context context = getContext();
                    if (context == null) {
                        return true;
                    }
                    PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                    if (powerManager != null && powerManager.isIgnoringBatteryOptimizations(context.getPackageName())) {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    } else {
                        Intent request = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.parse("package:" + context.getPackageName()));
                        try {
                            startActivity(request);
                        } catch (ActivityNotFoundException e) {
                            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                        }
                    }
                    return true;
                });
            }

            preference = findPreference("regenerate_api_key");
            if (preference != null) {
                preference.setOnPreferenceClickListener(p -> {
                    Context context = getContext();
                    if (context == null) {
                        return true;
                    }
                    String key = ApiKey.regenerateApiKey(context);
                    EditTextPreference apiKeyPreference = findPreference("api_key");
                    if (apiKeyPreference != null) {
                        apiKeyPreference.setText(key);
                    }
                    Toast.makeText(context, R.string.settings_api_key_regenerated, Toast.LENGTH_SHORT)
                            .show();
                    return true;
                });
            }

            preference = findPreference("import_local_audio_db");
            if (preference != null) {
                preference.setOnPreferenceClickListener(p -> {
                    if (importDatabaseLauncher != null) {
                        importDatabaseLauncher.launch(new String[] {"*/*"});
                    }
                    return true;
                });
            }

            preference = findPreference("delete_local_audio_db");
            if (preference != null) {
                preference.setOnPreferenceClickListener(p -> {
                    Context context = getContext();
                    if (context != null) {
                        new AlertDialog.Builder(context)
                                .setTitle(R.string.settings_delete_local_audio_confirm_title)
                                .setMessage(R.string.settings_delete_local_audio_confirm_message)
                                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                                    LocalAudioImporter.deleteDatabase(context);
                                    updateLocalAudioStatus();
                                })
                                .setNegativeButton(android.R.string.cancel, null)
                                .show();
                    }
                    return true;
                });
            }
        }
    }
}
