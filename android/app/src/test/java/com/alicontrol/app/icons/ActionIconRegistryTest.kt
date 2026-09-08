package com.alicontrol.app.icons

import com.alicontrol.app.dashboard.DefaultDashboardFactory
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionIconRegistryTest {

    @Test
    fun everyDefaultDashboardActionHasADedicatedVectorIcon() {
        val defaultActionIds = DefaultDashboardFactory.create()
            .pages
            .flatMap { it.widgets }
            .flatMap { it.referencedActionIds() }
            .toSet()

        for (actionId in defaultActionIds) {
            assertTrue("missing dedicated icon for $actionId", ActionIconRegistry.hasDedicatedIcon(actionId))
        }
    }
}
