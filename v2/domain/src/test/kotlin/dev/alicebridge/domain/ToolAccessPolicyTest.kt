package dev.alicebridge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolAccessPolicyTest {
    @Test fun supportedModelAndLiveAccessMatrix() {
        val models = listOf("lite1", "lite2", "mini1", "mini2", "mini3", "station1", "station2", "midi", "cucumber", "max", "unknown", "")
        for (model in models) for (adb in listOf(false, true)) for (root in listOf(false, true)) for (profile in listOf(false, true)) {
            val label = "model=$model adb=$adb root=$root profile=$profile"
            val access = ToolAccessPolicy.of(adb, root, profile, model)
            val known = model != "unknown" && model.isNotEmpty()
            val mutation = known && adb && root
            val wifi = mutation && model != "max" && (model != "station1" || profile)
            assertEquals("$label mutation", mutation, access.tools)
            assertEquals("$label installers", mutation, access.installers)
            assertEquals("$label terminal", adb, access.terminal)
            assertEquals("$label wifi", wifi, access.wifi)
            assertEquals("$label OTA", mutation && model != "max", access.otaCleanup)
            assertEquals("$label mutation explanation", !mutation, access.mutationDisabledReason.isNotBlank())
            assertEquals("$label wifi explanation", !wifi, access.wifiDisabledReason.isNotBlank())
        }
    }

    @Test fun optionalProfileNeverChangesLinuxCapabilities() {
        for (model in listOf("lite1", "lite2", "mini1", "mini2", "mini3", "station2", "midi", "cucumber", "street")) {
            assertEquals(model, ToolAccessPolicy.of(true, true, true, model), ToolAccessPolicy.of(true, true, false, model))
        }
    }

    @Test fun maxExplainsItsOwnWifiRestrictionNotStation1Safety() {
        for (profile in listOf(false, true)) {
            val reason = ToolAccessPolicy.of(true, true, profile, "max").wifiDisabledReason
            assertTrue(reason.contains("Макс"))
            assertTrue(!reason.contains("Станции 1"))
        }
    }
    @Test fun factoryWifiCannotBypassRetailTransitionOnRootAlone() {
        for (model in listOf("lite2", "mini3")) {
            val access = ToolAccessPolicy.of(true, true, true, model, factoryTransitionPending = true)
            assertTrue(access.terminal)
            assertTrue(!access.wifi)
            assertTrue(access.wifiDisabledReason.contains("factory"))
        }
    }
}
 