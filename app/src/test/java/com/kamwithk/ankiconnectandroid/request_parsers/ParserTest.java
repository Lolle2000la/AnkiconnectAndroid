package com.kamwithk.ankiconnectandroid.request_parsers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class ParserTest {

    @Test
    public void parsesAction() {
        JsonObject request = Parser.parse("{\"action\":\"findNotes\",\"params\":{\"query\":\"deck:Foo\"}}");
        assertEquals("findNotes", Parser.get_action(request));
    }

    @Test
    public void usesVersionFallbackWhenMissing() {
        JsonObject request = Parser.parse("{\"action\":\"version\"}");
        assertEquals(4, Parser.get_version(request, 4));
    }

    @Test
    public void usesProvidedVersion() {
        JsonObject request = Parser.parse("{\"action\":\"version\",\"version\":6}");
        assertEquals(6, Parser.get_version(request, 4));
    }

    @Test
    public void readsNoteQuery() {
        JsonObject request = Parser.parse("{\"action\":\"findCards\",\"params\":{\"query\":\"nid:123\"}}");
        assertEquals("nid:123", Parser.getNoteQuery(request));
    }

    @Test
    public void readsCardIds() {
        JsonObject request = Parser.parse("{\"action\":\"suspend\",\"params\":{\"cards\":[11,22,33]}}");
        assertEquals(List.of(11L, 22L, 33L), Parser.getCardIds(request));
    }

    @Test
    public void readsNoteIds() {
        JsonObject request = Parser.parse("{\"action\":\"notesInfo\",\"params\":{\"notes\":[5,6]}}");
        assertEquals(List.of(5L, 6L), Parser.getNoteIds(request));
    }

    @Test
    public void readsAddNoteFieldsDeckModelAndTags() {
        String json = "{\"action\":\"addNote\",\"params\":{\"note\":{"
                + "\"deckName\":\"Deck\",\"modelName\":\"Model\","
                + "\"fields\":{\"Front\":\"a\"},\"tags\":[\"t1\",\"t2\"]}}}";
        JsonObject request = Parser.parse(json);

        assertEquals("Deck", Parser.getDeckName(request));
        assertEquals("Model", Parser.getModelName(request));
        Map<String, String> fields = Parser.getNoteValues(request);
        assertEquals("a", fields.get("Front"));
        Set<String> tags = Parser.getNoteTags(request);
        assertTrue(tags.contains("t1"));
        assertTrue(tags.contains("t2"));
    }

    @Test
    public void readsModelNameFromParam() {
        JsonObject request = Parser.parse("{\"action\":\"modelFieldNames\",\"params\":{\"modelName\":\"Basic\"}}");
        assertEquals("Basic", Parser.getModelNameFromParam(request));
    }

    @Test
    public void readsMultiActions() {
        JsonObject request = Parser.parse("{\"action\":\"multi\",\"params\":{\"actions\":["
                + "{\"action\":\"version\"},{\"action\":\"deckNames\"}]}}");
        assertEquals(2, Parser.getMultiActions(request).size());
    }
}
