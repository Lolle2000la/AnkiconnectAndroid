package com.kamwithk.ankiconnectandroid.routing.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Exercises the in-app import path: the database is streamed to {@code android.db.part},
 * validated, and only then swapped in. Validation failures must leave the previous database
 * untouched.
 */
@RunWith(RobolectricTestRunner.class)
public class LocalAudioImporterTest {

    private Context context;

    @Before
    public void setUp() {
        LocalAudioDatabase.invalidate();
        context = ApplicationProvider.getApplicationContext();
    }

    @After
    public void tearDown() {
        LocalAudioDatabase.invalidate();
    }

    private File sourceDatabase(boolean withLocalAudioTables) throws IOException {
        File databaseFile = File.createTempFile("local-audio-source", ".db", context.getCacheDir());
        SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(databaseFile, null);
        if (withLocalAudioTables) {
            database.execSQL("CREATE TABLE entries (id INTEGER PRIMARY KEY, expression TEXT)");
            database.execSQL("CREATE TABLE android (id INTEGER PRIMARY KEY, file TEXT, source TEXT, data BLOB)");
        } else {
            database.execSQL("CREATE TABLE unrelated (id INTEGER)");
        }
        database.close();
        return databaseFile;
    }

    private File nonDatabaseFile(String contents) throws IOException {
        File file = File.createTempFile("local-audio-bad", ".db", context.getCacheDir());
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(contents.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    private LocalAudioImporter.Result importFile(File file) {
        return LocalAudioImporter.importFromUri(context, Uri.fromFile(file), null, new AtomicBoolean(false));
    }

    @Test
    public void importsAValidDatabase() throws IOException {
        LocalAudioImporter.Result result = importFile(sourceDatabase(true));

        assertTrue(result.message, result.success);
        File target = LocalAudioDatabase.resolveDatabaseFile(context);
        assertTrue(target.isFile());
        assertTrue(target.length() > 0);
    }

    @Test
    public void reportsProgressWhileCopying() throws IOException {
        AtomicLong copied = new AtomicLong(-1);
        LocalAudioImporter.importFromUri(
                context,
                Uri.fromFile(sourceDatabase(true)),
                (bytes, total) -> copied.set(bytes),
                new AtomicBoolean(false));

        assertTrue("progress was not reported", copied.get() > 0);
    }

    @Test
    public void rejectsFilesThatAreNotSqlite() throws IOException {
        LocalAudioImporter.Result result = importFile(nonDatabaseFile("definitely not a database"));

        assertFalse(result.success);
        assertTrue(result.message, result.message.contains("not a SQLite database"));
    }

    @Test
    public void rejectsEmptyFiles() throws IOException {
        File empty = File.createTempFile("local-audio-empty", ".db", context.getCacheDir());

        LocalAudioImporter.Result result = importFile(empty);

        assertFalse(result.success);
        assertTrue(result.message, result.message.contains("empty"));
    }

    @Test
    public void rejectsDatabasesWithoutLocalAudioTables() throws IOException {
        LocalAudioImporter.Result result = importFile(sourceDatabase(false));

        assertFalse(result.success);
        assertTrue(result.message, result.message.contains("missing the local audio tables"));
    }

    @Test
    public void respectsCancellation() throws IOException {
        AtomicBoolean cancelled = new AtomicBoolean(true);
        LocalAudioImporter.Result result =
                LocalAudioImporter.importFromUri(context, Uri.fromFile(sourceDatabase(true)), null, cancelled);

        assertFalse(result.success);
        assertTrue(result.message, result.message.contains("cancel"));
    }

    @Test
    public void keepsTheExistingDatabaseWhenImportFails() throws IOException {
        LocalAudioImporter.Result first = importFile(sourceDatabase(true));
        assertTrue(first.message, first.success);

        File target = LocalAudioDatabase.resolveDatabaseFile(context);
        long sizeBefore = target.length();

        LocalAudioImporter.Result second = importFile(nonDatabaseFile("broken"));

        assertFalse(second.success);
        assertTrue("previous database was removed", target.isFile());
        assertEquals(sizeBefore, target.length());
        assertFalse("temporary .part file was left behind", new File(target.getPath() + ".part").exists());
    }

    @Test
    public void deletesTheDatabaseAndSidecars() throws IOException {
        assertTrue(importFile(sourceDatabase(true)).success);
        File target = LocalAudioDatabase.resolveDatabaseFile(context);
        File sidecar = new File(target.getPath() + "-wal");
        assertTrue(sidecar.createNewFile());

        assertTrue(LocalAudioImporter.deleteDatabase(context));

        assertFalse(target.exists());
        assertFalse(sidecar.exists());
    }

    @Test
    public void validatesRequiredTablesAreReadable() throws IOException {
        assertTrue(importFile(sourceDatabase(true)).success);

        File target = LocalAudioDatabase.resolveDatabaseFile(context);
        SQLiteDatabase database =
                SQLiteDatabase.openDatabase(target.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try (Cursor cursor =
                database.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='entries'", null)) {
            assertTrue(cursor.moveToFirst());
        } finally {
            database.close();
        }
    }
}
