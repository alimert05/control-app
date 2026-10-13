package com.alicontrol.app.ble

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AliControlBleProfileTest {

    @Test
    fun uuidsAreAllDistinct() {
        val uuids = setOf(
            AliControlBleProfile.SERVICE_UUID,
            AliControlBleProfile.DISCOVERY_SERVICE_UUID,
            AliControlBleProfile.COMMAND_CHARACTERISTIC_UUID,
            AliControlBleProfile.STATE_CHARACTERISTIC_UUID,
            AliControlBleProfile.CLIENT_CHARACTERISTIC_CONFIG_UUID
        )
        assertEquals(5, uuids.size)
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
        // Preserved verbatim from the original protocol; the Windows agent hosts exactly these.
        assertEquals("7eb4052b-fc5f-436f-ae69-c3c9e7e9809f", AliControlBleProfile.SERVICE_UUID.toString())
        assertEquals("6544209f-88b6-432e-9db1-c672b7a31916", AliControlBleProfile.COMMAND_CHARACTERISTIC_UUID.toString())
        assertEquals("71dbc91d-eb99-4a50-b875-c3f045f4ea91", AliControlBleProfile.STATE_CHARACTERISTIC_UUID.toString())
        assertNotEquals(AliControlBleProfile.COMMAND_CHARACTERISTIC_UUID, AliControlBleProfile.STATE_CHARACTERISTIC_UUID)
    }

    @Test
    fun theDiscoveryAliasMatchesTheWindowsAgentsAndIsACompactBaseUuid() {
        // Must equal AliControlBleProfile.DiscoveryServiceUuid in the Windows agent.
        assertEquals("a11c0e01-0000-1000-8000-00805f9b34fb", AliControlBleProfile.DISCOVERY_SERVICE_UUID.toString())
        assertTrue(AliControlBleProfile.DISCOVERY_SERVICE_UUID.toString().endsWith("-0000-1000-8000-00805f9b34fb"))
    }

    @Test
    fun aScanLooksForTheFullUuidFirstThenTheCompactAlias() {
        assertEquals(
            listOf(AliControlBleProfile.SERVICE_UUID, AliControlBleProfile.DISCOVERY_SERVICE_UUID),
            AliControlBleProfile.SCAN_SERVICE_UUIDS
        )
    }

    @Test
    fun theFullServiceIsPreferredWhenADeviceExposesBoth() {
        val picked = AliControlBleProfile.pickService { uuid -> "service:$uuid" }
        assertEquals("service:${AliControlBleProfile.SERVICE_UUID}", picked)
    }

    @Test
    fun theCompactServiceIsUsedWhenTheDeviceOnlyHostsThat() {
        val picked = AliControlBleProfile.pickService { uuid ->
            if (uuid == AliControlBleProfile.DISCOVERY_SERVICE_UUID) "alias" else null
        }
        assertEquals("alias", picked)
    }

    @Test
    fun aDeviceWithNeitherServiceIsNotAnAliControlDesktop() {
        assertNull(AliControlBleProfile.pickService<String> { null })
    }

    @Test
    fun noOtherServiceUuidIsEverAccepted() {
        val asked = mutableListOf<UUID>()
        AliControlBleProfile.pickService<String> { uuid ->
            asked.add(uuid)
            null
        }
        assertEquals(AliControlBleProfile.SCAN_SERVICE_UUIDS, asked)
    }
}
