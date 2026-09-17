package com.kamwithk.ankiconnectandroid;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Process-wide state of the AnkiConnect server so the UI can show the correct control.
 *
 * <p>All updates happen on the main thread (service lifecycle callbacks and the activity's
 * button handlers), so listeners are invoked synchronously on the main thread.
 */
public final class ServiceState {
    public enum State {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING
    }

    public interface Listener {
        void onStateChanged(State state);
    }

    private static final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private static volatile State state = State.STOPPED;

    private ServiceState() {}

    public static State get() {
        return state;
    }

    static void set(State newState) {
        if (newState == state) {
            return;
        }
        state = newState;
        for (Listener listener : listeners) {
            listener.onStateChanged(newState);
        }
    }

    /** Registers a listener and immediately reports the current state. */
    public static void addListener(Listener listener) {
        listeners.add(listener);
        listener.onStateChanged(state);
    }

    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }
}
