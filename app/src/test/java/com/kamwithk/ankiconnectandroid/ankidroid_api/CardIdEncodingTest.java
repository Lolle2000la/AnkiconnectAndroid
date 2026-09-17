package com.kamwithk.ankiconnectandroid.ankidroid_api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * The local audio import and get audio actions depend on the reversible card-ID encoding in
 * {@link CardAPI}. A change here silently breaks Yomitan's "Suspend new cards" flow.
 */
@RunWith(RobolectricTestRunner.class)
public class CardIdEncodingTest {

    @Test
    public void roundTripsNoteIdAndOrdinal() {
        long[] noteIds = {1L, 42L, 1_700_000_000_000L, (1L << 53) - 1};
        int[] ordinals = {0, 1, 5, 1023};

        for (long noteId : noteIds) {
            for (int ord : ordinals) {
                long cardId = CardAPI.encodeCardId(noteId, ord);
                assertEquals(noteId, CardAPI.decodeNoteId(cardId));
                assertEquals(ord, CardAPI.decodeOrd(cardId));
            }
        }
    }

    @Test
    public void differentOrdinalsProduceDifferentIds() {
        assertNotEquals(CardAPI.encodeCardId(7L, 0), CardAPI.encodeCardId(7L, 1));
    }

    @Test
    public void differentNotesProduceDifferentIds() {
        assertNotEquals(CardAPI.encodeCardId(7L, 0), CardAPI.encodeCardId(8L, 0));
    }
}
