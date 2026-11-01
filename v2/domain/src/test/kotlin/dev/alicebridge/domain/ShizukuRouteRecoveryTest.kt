package dev.alicebridge.domain
import org.junit.Assert.*
import org.junit.Test
class ShizukuRouteRecoveryTest {
    @Test fun knownRouteLossCanBeRebuilt() {
        assertTrue(ShizukuRouteRecovery.allowed("Android сменил маршрут хотспота: testtun6 → wlan0",0))
        assertTrue(ShizukuRouteRecovery.allowed("IllegalStateException: Маршрут хотспота Shizuku отсутствует более 3 секунд",1))
        assertTrue(ShizukuRouteRecovery.allowed("Тестовая сеть Shizuku потеряна",0))
    }
    @Test fun recoveryBudgetPreventsRestartLoop() {
        assertFalse(ShizukuRouteRecovery.allowed("Android сменил маршрут хотспота",2))
        assertFalse(ShizukuRouteRecovery.allowed("Тестовая сеть Shizuku потеряна",10))
    }
    @Test fun otherFailuresAreNotRouteRecovery() {
        for (s in listOf("Неверный DNS для quasar", "Shizuku отключён", "Сетевой движок Shizuku остановился", "Отключите VPN", "Read timed out"))
            assertFalse(s,ShizukuRouteRecovery.allowed(s,0))
    }
}
