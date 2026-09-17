package com.kamwithk.ankiconnectandroid.ankidroid_api;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class UtilityTest {

    private static final String FIELD_SEPARATOR = Character.toString('\u001f');

    @Test
    public void splitTagsReturnsNullForNull() {
        assertNull(Utility.splitTags(null));
    }

    @Test
    public void splitTagsTrimsAndSplitsOnWhitespace() {
        assertArrayEquals(new String[] {"a", "b", "c"}, Utility.splitTags("  a b   c "));
    }

    @Test
    public void splitFieldsReturnsNullForNull() {
        assertNull(Utility.splitFields(null));
    }

    @Test
    public void splitFieldsKeepsTrailingEmptyFields() {
        assertArrayEquals(
                new String[] {"a", "b", ""}, Utility.splitFields("a" + FIELD_SEPARATOR + "b" + FIELD_SEPARATOR));
    }

    @Test
    public void checksumIgnoresHtmlTags() {
        assertEquals(Utility.getFieldChecksum("word"), Utility.getFieldChecksum("<b>wo</b>rd"));
    }

    @Test
    public void checksumIgnoresScriptsAndStyles() {
        assertEquals(
                Utility.getFieldChecksum("word"),
                Utility.getFieldChecksum("<script>x()</script><style>.a{}</style>word"));
    }

    @Test
    public void checksumIsStable() {
        assertEquals(Utility.getFieldChecksum("hello"), Utility.getFieldChecksum("hello"));
    }
}
