package com.kamwithk.ankiconnectandroid;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;

/**
 * The main screen shows the start button, stop button or a progress indicator based on this state,
 * so its notification behaviour is worth pinning down.
 */
public class ServiceStateTest {

    @After
    public void resetState() {
        ServiceState.set(ServiceState.State.STOPPED);
    }

    @Test
    public void addingAListenerReportsTheCurrentStateImmediately() {
        ServiceState.set(ServiceState.State.RUNNING);

        List<ServiceState.State> observed = new ArrayList<>();
        ServiceState.Listener listener = observed::add;
        ServiceState.addListener(listener);
        ServiceState.removeListener(listener);

        assertEquals(List.of(ServiceState.State.RUNNING), observed);
    }

    @Test
    public void notifiesOnEachTransition() {
        ServiceState.set(ServiceState.State.STOPPED);

        List<ServiceState.State> observed = new ArrayList<>();
        ServiceState.Listener listener = observed::add;
        ServiceState.addListener(listener);
        ServiceState.set(ServiceState.State.STARTING);
        ServiceState.set(ServiceState.State.RUNNING);
        ServiceState.set(ServiceState.State.STOPPING);
        ServiceState.set(ServiceState.State.STOPPED);
        ServiceState.removeListener(listener);

        assertEquals(
                List.of(
                        ServiceState.State.STOPPED,
                        ServiceState.State.STARTING,
                        ServiceState.State.RUNNING,
                        ServiceState.State.STOPPING,
                        ServiceState.State.STOPPED),
                observed);
    }

    @Test
    public void doesNotNotifyWhenTheStateIsUnchanged() {
        ServiceState.set(ServiceState.State.RUNNING);

        List<ServiceState.State> observed = new ArrayList<>();
        ServiceState.Listener listener = observed::add;
        ServiceState.addListener(listener);
        ServiceState.set(ServiceState.State.RUNNING);
        ServiceState.removeListener(listener);

        assertEquals(1, observed.size());
    }

    @Test
    public void removedListenersAreNotNotified() {
        List<ServiceState.State> observed = new ArrayList<>();
        ServiceState.Listener listener = observed::add;
        ServiceState.addListener(listener);
        ServiceState.removeListener(listener);

        ServiceState.set(ServiceState.State.RUNNING);

        assertEquals(1, observed.size());
    }
}
