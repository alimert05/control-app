package com.alicontrol.app.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure decision logic inside [BluetoothTransport] — the parts
 * that don't need a real Android/BluetoothGatt to exercise: connection-state
 * readiness, reconnect scheduling decisions, retry-budget/backoff, and
 * MTU-based message size validation.
 */
class BluetoothTransportTest {

    // --- Transport-ready state ---

    @Test
    fun onlyConnectedStateIsReady() {
        assertTrue(BluetoothTransport.isTransportReady(BluetoothTransport.ConnectionState.CONNECTED))
        assertFalse(BluetoothTransport.isTransportReady(BluetoothTransport.ConnectionState.PREPARING))
        assertFalse(BluetoothTransport.isTransportReady(BluetoothTransport.ConnectionState.CONNECTING))
        assertFalse(BluetoothTransport.isTransportReady(BluetoothTransport.ConnectionState.SCANNING))
        assertFalse(BluetoothTransport.isTransportReady(BluetoothTransport.ConnectionState.DISCONNECTED))
    }

    // --- Reconnect decision logic ---

    @Test
    fun userInitiatedDisconnectNeverRetries() {
        assertFalse(BluetoothTransport.shouldScheduleReconnect(userInitiatedDisconnect = true, wasFullyConnected = true, autoReconnecting = true))
        assertFalse(BluetoothTransport.shouldScheduleReconnect(userInitiatedDisconnect = true, wasFullyConnected = false, autoReconnecting = false))
    }

    @Test
    fun unexpectedDropFromFullyConnectedRetries() {
        assertTrue(BluetoothTransport.shouldScheduleReconnect(userInitiatedDisconnect = false, wasFullyConnected = true, autoReconnecting = false))
    }

    @Test
    fun failedReconnectAttemptContinuesTheSequence() {
        assertTrue(BluetoothTransport.shouldScheduleReconnect(userInitiatedDisconnect = false, wasFullyConnected = false, autoReconnecting = true))
    }

    @Test
    fun freshConnectionAttemptFailureDoesNotRetry() {
        // Not user-initiated, never reached CONNECTED, and no reconnect sequence in progress —
        // this is a first-time connect failure, which is the manual-retry-via-ConnectActivity path.
        assertFalse(BluetoothTransport.shouldScheduleReconnect(userInitiatedDisconnect = false, wasFullyConnected = false, autoReconnecting = false))
    }

    // --- Retry budget / backoff ---

    @Test
    fun reconnectDelaysIncreaseAndAreBounded() {
        val delays = BluetoothTransport.RECONNECT_DELAYS_MS
        assertEquals(3, delays.size)
        for (i in 1 until delays.size) {
            assertTrue("delay at $i should be >= delay at ${i - 1}", delays[i] >= delays[i - 1])
        }
    }

    @Test
    fun nextReconnectDelayWalksThroughTheBudgetThenStops() {
        val delays = longArrayOf(1_000L, 2_000L, 3_000L)
        assertEquals(1_000L, BluetoothTransport.nextReconnectDelayMs(0, delays))
        assertEquals(2_000L, BluetoothTransport.nextReconnectDelayMs(1, delays))
        assertEquals(3_000L, BluetoothTransport.nextReconnectDelayMs(2, delays))
        assertNull(BluetoothTransport.nextReconnectDelayMs(3, delays))
        assertNull(BluetoothTransport.nextReconnectDelayMs(99, delays))
    }

    // --- MTU / message size validation ---

    @Test
    fun messageFittingUnderMtuIsNotOversized() {
        assertFalse(BluetoothTransport.exceedsMtuPayload(messageSizeBytes = 40, mtu = 185))
        assertFalse(BluetoothTransport.exceedsMtuPayload(messageSizeBytes = 20, mtu = 23))
    }

    @Test
    fun messageExceedingMtuIsOversized() {
        // The canonical volume_up command is 39 bytes — this is literally the
        // shape of the original MTU-truncation bug: at the un-negotiated
        // default MTU of 23 (20 usable bytes), it must be refused, not sent.
        assertTrue(BluetoothTransport.exceedsMtuPayload(messageSizeBytes = 39, mtu = 23))
    }

    @Test
    fun exactFitIsNotOversized() {
        // mtu=23, overhead=3 -> exactly 20 usable bytes.
        assertFalse(BluetoothTransport.exceedsMtuPayload(messageSizeBytes = 20, mtu = 23, attOverheadBytes = 3))
        assertTrue(BluetoothTransport.exceedsMtuPayload(messageSizeBytes = 21, mtu = 23, attOverheadBytes = 3))
    }
}
