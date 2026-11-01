package dev.alicebridge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuRecoveryTest {
    @Test fun wrongDnsIsNotPresentedAsAMediaTekServiceBug() {
        val advice = ShizukuRecovery.advice("Неверный DNS для quasar: UDP=[192.168.1.118]", true)
        assertEquals(ShizukuFailure.DNS, advice.failure)
        assertFalse(advice.offerForks)
    }

    @Test fun actualMediaTekUserServiceFailureSuggestsRelevantForks() {
        val advice = ShizukuRecovery.advice("Не удалось запустить UserService Shizuku", true)
        assertEquals(ShizukuFailure.USER_SERVICE, advice.failure)
        assertTrue(advice.offerForks)
        assertTrue(advice.detail.contains("thedjchi"))
    }

    @Test fun backendContractMismatchCannotBeFixedByChangingShizuku() {
        assertEquals(
            ShizukuFailure.BACKEND,
            ShizukuRecovery.advice("Обновите локальный backend: feedback_schema=2", false).failure,
        )
    }
}
