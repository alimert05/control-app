package com.alicontrol.app.actions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionDispatcherTest {

    @Test
    fun disconnectedStateNeverAllowsRemoteExecution() {
        assertFalse(ActionDispatcher.shouldSend(isConnected = false, requiresConfirmation = false, userConfirmed = true))
        assertFalse(ActionDispatcher.shouldSend(isConnected = false, requiresConfirmation = true, userConfirmed = true))
    }

    @Test
    fun confirmationActionsOnlySendAfterConfirmation() {
        assertFalse(ActionDispatcher.shouldSend(isConnected = true, requiresConfirmation = true, userConfirmed = false))
        assertTrue(ActionDispatcher.shouldSend(isConnected = true, requiresConfirmation = true, userConfirmed = true))
    }

    @Test
    fun connectedSafeActionCanSendImmediately() {
        assertTrue(ActionDispatcher.shouldSend(isConnected = true, requiresConfirmation = false, userConfirmed = false))
    }
}
