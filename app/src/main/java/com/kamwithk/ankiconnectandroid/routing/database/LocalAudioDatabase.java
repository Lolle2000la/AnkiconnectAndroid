package com.kamwithk.ankiconnectandroid.routing.database;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Resolves the local-audio {@code android.db} location and owns the process-wide Room
 * instance for it.
 *
 * <p>Previously {@code LocalAudioAPIRouting} built a new Room database for every HTTP
 * request and never closed it, leaking native SQLite connections and file descriptors
 * until the local audio server started refusing requests. This class keeps a single
 * instance and only reopens it when the resolved file changes.
 */
public final class LocalAudioDatabase {
    public static final String DB_NAME = "android.db";

    private static final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private static EntriesDatabase instance;
    private static String instancePath;

    private LocalAudioDatabase() {
    }

    /**
     * Resolves the database file from the {@code storage_location}/{@code storage_dir_path}
     * preferences, falling back to the app-specific external files directory when the
     * configured path is not readable.
     */
    public static File resolveDatabaseFile(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String storageDevice = prefs.getString("storage_location", "");
        String storageDir = prefs.getString("storage_dir_path", "");

        Path preferred = Paths.get(storageDevice, storageDir, DB_NAME);
        if (Files.isReadable(preferred)) {
            return preferred.toFile();
        }

        File externalFilesDir = context.getExternalFilesDir(null);
        if (externalFilesDir != null) {
            return new File(externalFilesDir, DB_NAME);
        }
        return preferred.toFile();
    }

    /**
     * Returns the shared {@link EntriesDatabase} for the given path, opening it on first
     * use or when the path changes.
     */
    public static EntriesDatabase get(Context context, String path) {
        lock.readLock().lock();
        try {
            if (instance != null && path.equals(instancePath)) {
                return instance;
            }
        } finally {
            lock.readLock().unlock();
        }

        lock.writeLock().lock();
        try {
            if (instance == null || !path.equals(instancePath)) {
                closeLocked();
                instance = Room.databaseBuilder(context.getApplicationContext(),
                                EntriesDatabase.class, path)
                        // Avoid -wal/-shm sidecars, which make swapping the database file harder.
                        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                        .build();
                instancePath = path;
            }
            return instance;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Closes the shared instance so the next {@link #get} call reopens it. */
    public static void invalidate() {
        lock.writeLock().lock();
        try {
            closeLocked();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static void closeLocked() {
        if (instance != null) {
            try {
                instance.close();
            } catch (Exception ignored) {
                // Closing a database that failed to open must not mask the caller's work.
            }
            instance = null;
            instancePath = null;
        }
    }
}
