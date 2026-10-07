package dev.petrov.ymplayer2.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InternetConnectionTest {
    private class Network(connected: Boolean) : InternetConnection {
        override val available = MutableStateFlow(connected)
        var refreshes = 0
        override fun refresh() { refreshes++ }
    }
    @Test fun absenceAppearsAfterExactlyFiveSeconds() = runTest {
        val network = Network(false); val check = InternetCheck(network, backgroundScope)
        runCurrent(); advanceTimeBy(4999); runCurrent()
        assertEquals(InternetStatus.WAITING, check.state.value)
        advanceTimeBy(1); runCurrent(); assertEquals(InternetStatus.OFFLINE, check.state.value)
        check.close()
    }
    @Test fun connectionWithinGraceCancelsErrorAndReconnectsOnce() = runTest {
        val network = Network(false); val callbacks = mutableListOf<Boolean>()
        val check = InternetCheck(network, backgroundScope, callbacks::add)
        runCurrent(); advanceTimeBy(3500); network.available.value = true; runCurrent()
        advanceTimeBy(6000); runCurrent()
        assertEquals(InternetStatus.CONNECTED, check.state.value); assertEquals(listOf(false), callbacks)
        check.close()
    }
    @Test fun retryStartsFreshGraceAndLateConnectionRetainsManualIntent() = runTest {
        val network = Network(false); val callbacks = mutableListOf<Boolean>()
        val check = InternetCheck(network, backgroundScope, callbacks::add)
        runCurrent(); advanceTimeBy(5000); runCurrent(); check.retry(); runCurrent()
        assertEquals(2, network.refreshes); assertEquals(InternetStatus.WAITING, check.state.value)
        advanceTimeBy(4999); runCurrent(); assertEquals(InternetStatus.WAITING, check.state.value)
        advanceTimeBy(1); runCurrent(); assertEquals(InternetStatus.OFFLINE, check.state.value)
        network.available.value = true; runCurrent(); assertEquals(listOf(true), callbacks)
        check.close()
    }
    @Test fun leavingCancelsOldCheckAndEachNewSurfaceGetsFullGrace() = runTest {
        val network = Network(false); var callbacks = 0
        val old = InternetCheck(network, backgroundScope, { callbacks++ })
        runCurrent(); advanceTimeBy(4000); old.close()
        val fresh = InternetCheck(network, backgroundScope, { callbacks++ })
        runCurrent(); advanceTimeBy(4999); runCurrent(); assertEquals(InternetStatus.WAITING, fresh.state.value)
        network.available.value = true; runCurrent(); assertEquals(1, callbacks)
        fresh.close()
    }
    @Test fun alreadyConnectedDoesNotReloadOrWait() = runTest {
        val network = Network(true); var callbacks = 0
        val check = InternetCheck(network, backgroundScope, { callbacks++ })
        runCurrent(); assertEquals(InternetStatus.CONNECTED, check.state.value); assertEquals(0, callbacks)
        check.close()
    }
}
