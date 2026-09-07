package com.alicontrol.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AliControlBleProfileTest {

    @Test
    fun uuidsAreAllDistinct() {
        val uuids = setOf(
            AliControlBleProfile.SERVICE_UUID,
            AliControlBleProfile.COMMAND_CHARACTERISTIC_UUID,
            AliControlBleProfile.STATE_CHARACTERISTIC_UUID,
            AliControlBleProfile.CLIENT_CHARACTERISTIC_CONFIG_UUID
        )
        assertEquals(4, uuids.size)
    }

    @Test
    fun clientCharacteristicConfigIsTheStandardBluetoothDescriptor() {
        assertEquals(
            "00002902-0000-1000-8000-00805f9b34fb",
            AliControlBleProfile.CLIENT_CHARACTERISTIC_CONFIG_UUID.toString()
        )
    }

    @Test
    fun serviceAndCharacteristicUuidsAreStable() {
        assertEquals("7eb4052b-fc5f-436f-ae69-c3c9e7e9809f", AliControlBleProfile.SERVICE_UUID.toString())
        assertEquals("6544209f-88b6-432e-9db1-c672b7a31916", AliControlBleProfile.COMMAND_CHARACTERISTIC_UUID.toString())
        assertEquals("71dbc91d-eb99-4a50-b875-c3f045f4ea91", AliControlBleProfile.STATE_CHARACTERISTIC_UUID.toString())
        assertNotEquals(AliControlBleProfile.COMMAND_CHARACTERISTIC_UUID, AliControlBleProfile.STATE_CHARACTERISTIC_UUID)
    }
}
