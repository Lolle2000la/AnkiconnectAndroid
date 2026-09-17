package com.kamwithk.ankiconnectandroid.routing.database;

import android.content.Context;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import java.io.File;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Resolves the local-audio {@code android.db} location and owns the process-wide Room
 * instance for it.
 *
 * <p>Databases live in the app-specific external files directory, which needs no storage
 * permission, and are brought in through the in-app import (Storage Access Framework).
 * Previously a new Room database was built for every HTTP request and never closed,
 * leaking native SQLite connections and file descriptors until the local audio server
 * started refusing requests. This class keeps a single instance and only reopens it when
 * the resolved file changes.
 */
public final class LocalAudioDatabase {
    public static final String DB_NAME = "android.db";

    private static final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private static EntriesDatabase instance;
    private static String instancePath;

    private LocalAudioDatabase() {}

    /**
     * The only supported database location: {@code getExternalFilesDir(null)} (falling back
     * to internal storage when external storage is unavailable).
     */
    public static File resolveDatabaseFile(Context context) {
        File externalFilesDir = context.getExternalFilesDir(null);
        if (externalFilesDir != null) {
            return new File(externalFilesDir, DB_NAME);
        }
        return new File(context.getFilesDir(), DB_NAME);
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
                instance = Room.databaseBuilder(context.getApplicationContext(), EntriesDatabase.class, path)
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
