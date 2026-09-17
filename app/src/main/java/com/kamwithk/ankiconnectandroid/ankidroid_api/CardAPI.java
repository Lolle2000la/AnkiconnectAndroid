package com.kamwithk.ankiconnectandroid.ankidroid_api;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import com.ichi2.anki.FlashCardsContract;

import java.util.ArrayList;
import java.util.List;

/**
 * Implements the card-level AnkiConnect actions that AnkiDroid's content provider can support.
 *
 * <p>AnkiDroid does not expose real Anki card IDs through {@link FlashCardsContract} (there is
 * no {@code Card._ID} and no card collection route in the versions we compile against), so card
 * IDs are synthesised from the {@code (noteId, ord)} pair that the provider does expose. The
 * encoding is reversible and only meaningful within this app.
 */
public class CardAPI {
    private static final int ORD_BITS = 10;
    private static final long ORD_MASK = (1L << ORD_BITS) - 1;

    private static final String[] CARD_PROJECTION = {
            FlashCardsContract.Card.NOTE_ID,
            FlashCardsContract.Card.CARD_ORD
    };

    private final ContentResolver resolver;
    private final NoteAPI noteAPI;

    public CardAPI(Context context, NoteAPI noteAPI) {
        this.resolver = context.getContentResolver();
        this.noteAPI = noteAPI;
    }

    /**
     * Returns synthesised card IDs for all cards whose notes match {@code query}.
     * The query is evaluated against notes (as AnkiDroid only exposes note search).
     */
    public ArrayList<Long> findCards(String query) {
        ArrayList<Long> cardIds = new ArrayList<>();
        for (Long noteId : noteAPI.findNotes(query)) {
            cardIds.addAll(getCardIdsForNote(noteId));
        }
        return cardIds;
    }

    private List<Long> getCardIdsForNote(long noteId) {
        List<Long> cardIds = new ArrayList<>();

        Uri noteUri = Uri.withAppendedPath(FlashCardsContract.Note.CONTENT_URI, Long.toString(noteId));
        Uri cardUri = Uri.withAppendedPath(noteUri, "cards");
        Cursor cursor = resolver.query(cardUri, CARD_PROJECTION, null, null, null);

        if (cursor != null) {
            try (cursor) {
                int noteIdIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Card.NOTE_ID);
                int ordIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Card.CARD_ORD);
                while (cursor.moveToNext()) {
                    cardIds.add(encodeCardId(cursor.getLong(noteIdIdx), cursor.getInt(ordIdx)));
                }
            }
        }
        return cardIds;
    }

    /** Suspends the given (synthesised) card IDs. Returns true if every card was suspended. */
    public boolean suspendCards(ArrayList<Long> cardIds) {
        boolean allSuspended = true;
        for (long cardId : cardIds) {
            ContentValues values = new ContentValues();
            values.put(FlashCardsContract.ReviewInfo.NOTE_ID, decodeNoteId(cardId));
            values.put(FlashCardsContract.ReviewInfo.CARD_ORD, decodeOrd(cardId));
            values.put(FlashCardsContract.ReviewInfo.SUSPEND, 1);
            int updated = resolver.update(FlashCardsContract.ReviewInfo.CONTENT_URI, values, null, null);
            allSuspended = allSuspended && updated > 0;
        }
        return allSuspended;
    }

    static long encodeCardId(long noteId, int ord) {
        return (noteId << ORD_BITS) | (ord & ORD_MASK);
    }

    static long decodeNoteId(long cardId) {
        return cardId >>> ORD_BITS;
    }

    static int decodeOrd(long cardId) {
        return (int) (cardId & ORD_MASK);
    }
}
