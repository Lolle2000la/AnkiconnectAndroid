package com.kamwithk.ankiconnectandroid.routing.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import java.io.File;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * The local audio server used to build a new Room database for every request and never close it,
 * which leaked connections until it stopped responding. These tests pin the single-instance
 * behaviour that fixed it.
 */
@RunWith(RobolectricTestRunner.class)
public class LocalAudioDatabaseTest {

    private Context context;
    private String path;

    @Before
    public void setUp() {
        LocalAudioDatabase.invalidate();
        context = ApplicationProvider.getApplicationContext();
        path = LocalAudioDatabase.resolveDatabaseFile(context).getAbsolutePath();
    }

    @After
    public void tearDown() {
        LocalAudioDatabase.invalidate();
    }

    @Test
    public void resolvesToTheAppSpecificExternalFilesDirectory() {
        File databaseFile = LocalAudioDatabase.resolveDatabaseFile(context);

        assertEquals(LocalAudioDatabase.DB_NAME, databaseFile.getName());
        File externalFilesDir = context.getExternalFilesDir(null);
        if (externalFilesDir != null) {
            assertEquals(new File(externalFilesDir, LocalAudioDatabase.DB_NAME), databaseFile);
        } else {
            assertEquals(new File(context.getFilesDir(), LocalAudioDatabase.DB_NAME), databaseFile);
        }
    }

    @Test
    public void reusesTheSameInstanceForTheSamePath() {
        EntriesDatabase first = LocalAudioDatabase.get(context, path);
        EntriesDatabase second = LocalAudioDatabase.get(context, path);

        assertSame(first, second);
    }

    @Test
    public void reopenAfterInvalidateReturnsANewInstance() {
        EntriesDatabase first = LocalAudioDatabase.get(context, path);
        LocalAudioDatabase.invalidate();
        EntriesDatabase second = LocalAudioDatabase.get(context, path);

        assertNotSame(first, second);
    }

    @Test
    public void usesDistinctInstancesForDistinctPaths() {
        EntriesDatabase first = LocalAudioDatabase.get(context, path);
        EntriesDatabase second = LocalAudioDatabase.get(context, path + ".other");

        assertNotSame(first, second);
        assertTrue(first != second);
    }
}
