package com.kamwithk.ankiconnectandroid.routing.database;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Imports a user-selected {@code android.db} into app storage, removing the need to use adb or a
 * file manager (Android 11+ hides {@code Android/data/<pkg>/files} from file managers).
 *
 * <p>The file is streamed to {@code android.db.part}, validated, and then atomically swapped in.
 * The server keeps serving the previous database until the swap succeeds.
 */
public final class LocalAudioImporter {
    private static final String TAG = "AnkiconnectAndroid";
    private static final byte[] SQLITE_MAGIC = "SQLite format 3\u0000".getBytes();
    private static final long MIN_FREE_MARGIN = 64L * 1024 * 1024;
    private static final int BUFFER_SIZE = 1024 * 1024;

    /** Reports copy progress; {@code totalBytes} is -1 when unknown. */
    public interface ProgressListener {
        void onProgress(long copiedBytes, long totalBytes);
    }

    public static final class Result {
        public final boolean success;
        public final String message;
        public final File databaseFile;

        Result(boolean success, String message, File databaseFile) {
            this.success = success;
            this.message = message;
            this.databaseFile = databaseFile;
        }
    }

    private LocalAudioImporter() {}

    /** The file that would currently be served. */
    public static File getDatabaseFile(Context context) {
        return LocalAudioDatabase.resolveDatabaseFile(context);
    }

    /**
     * Streams {@code uri} into the resolved database location. On success the previous
     * database (and its sidecars) are replaced.
     */
    public static Result importFromUri(Context context, Uri uri, ProgressListener listener, AtomicBoolean cancelled) {
        File target = getDatabaseFile(context);
        File parent = target.getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs())) {
            return new Result(false, "Cannot create destination directory", target);
        }

        File part = new File(parent, LocalAudioDatabase.DB_NAME + ".part");
        deleteQuietly(part);

        long total = querySize(context.getContentResolver(), uri);
        long available = parent.getUsableSpace();
        if (total > 0 && available < total + MIN_FREE_MARGIN) {
            return new Result(
                    false,
                    String.format(
                            Locale.US,
                            "Not enough free space: need about %d MB, have %d MB",
                            (total + MIN_FREE_MARGIN) / (1024 * 1024),
                            available / (1024 * 1024)),
                    target);
        }

        try {
            copy(context, uri, part, total, listener, cancelled);
        } catch (IOException e) {
            Log.w(TAG, "Local audio import failed", e);
            deleteQuietly(part);
            return new Result(false, "Import failed: " + e.getMessage(), target);
        }

        if (cancelled != null && cancelled.get()) {
            deleteQuietly(part);
            return new Result(false, "Import cancelled", target);
        }

        String validationError = validate(part);
        if (validationError != null) {
            deleteQuietly(part);
            return new Result(false, validationError, target);
        }

        try {
            swapIn(target, part);
        } catch (IOException e) {
            Log.w(TAG, "Could not replace local audio database", e);
            deleteQuietly(part);
            return new Result(false, "Could not replace database: " + e.getMessage(), target);
        }

        return new Result(true, "Imported local audio database", target);
    }

    /** Deletes the served database and its sidecars. */
    public static boolean deleteDatabase(Context context) {
        File target = getDatabaseFile(context);
        LocalAudioDatabase.invalidate();
        deleteSidecars(target);
        return deleteQuietly(target);
    }

    private static void copy(
            Context context, Uri uri, File part, long total, ProgressListener listener, AtomicBoolean cancelled)
            throws IOException {
        long copied = 0;
        byte[] buffer = new byte[BUFFER_SIZE];

        InputStream rawInput = context.getContentResolver().openInputStream(uri);
        if (rawInput == null) {
            throw new IOException("Could not open the selected file");
        }

        try (InputStream in = new BufferedInputStream(rawInput);
                FileOutputStream fileOut = new FileOutputStream(part);
                OutputStream out = new BufferedOutputStream(fileOut)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (cancelled != null && cancelled.get()) {
                    break;
                }
                out.write(buffer, 0, read);
                copied += read;
                if (listener != null) {
                    listener.onProgress(copied, total);
                }
            }
            out.flush();
            // Make sure the bytes hit disk before we replace the live database.
            fileOut.getFD().sync();
        } catch (IOException e) {
            deleteQuietly(part);
            throw e;
        }
    }

    /** Returns null when valid, otherwise a user-facing error message. */
    private static String validate(File part) {
        if (!part.isFile() || part.length() == 0) {
            return "The selected file is empty.";
        }

        byte[] header = new byte[SQLITE_MAGIC.length];
        try (InputStream in = new FileInputStream(part)) {
            int read = in.read(header);
            if (read < SQLITE_MAGIC.length || !java.util.Arrays.equals(header, SQLITE_MAGIC)) {
                return "The selected file is not a SQLite database.";
            }
        } catch (IOException e) {
            return "Could not read the imported file: " + e.getMessage();
        }

        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(part.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            if (!tableExists(db, "entries") || !tableExists(db, "android")) {
                return "The database is missing the local audio tables (entries, android).";
            }
        } catch (Exception e) {
            return "The database could not be opened: " + e.getMessage();
        } finally {
            if (db != null) {
                db.close();
            }
        }
        return null;
    }

    private static boolean tableExists(SQLiteDatabase db, String name) {
        Cursor cursor =
                db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", new String[] {name});
        if (cursor == null) {
            return false;
        }
        try (cursor) {
            return cursor.moveToFirst();
        }
    }

    private static void swapIn(File target, File part) throws IOException {
        // Close the live database before touching the file.
        LocalAudioDatabase.invalidate();
        deleteSidecars(target);
        try {
            Files.move(
                    part.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception atomicMoveFailed) {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteSidecars(File databaseFile) {
        deleteQuietly(new File(databaseFile.getPath() + "-wal"));
        deleteQuietly(new File(databaseFile.getPath() + "-shm"));
        deleteQuietly(new File(databaseFile.getPath() + "-journal"));
    }

    private static long querySize(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[] {OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) {
                    return cursor.getLong(index);
                }
            }
        } catch (Exception ignored) {
            // Size is only used for the free-space check and progress; unknown is fine.
        }
        return -1;
    }

    private static boolean deleteQuietly(File file) {
        if (file != null && file.exists()) {
            return file.delete();
        }
        return false;
    }
}
