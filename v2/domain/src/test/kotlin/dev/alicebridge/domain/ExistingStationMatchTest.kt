package dev.alicebridge.domain
import org.junit.Assert.*
import org.junit.Test
class ExistingStationMatchTest {
    @Test fun matchingBuildName() {
        assertTrue(ExistingStationMatch.matches("yandexmicro", "lite1", mapOf("BUILDNAME" to "yandexmicro-release")))
        assertTrue(ExistingStationMatch.matches("yandexmicro", "lite1", mapOf("MODEL" to "Yandex Station Micro")))
        assertTrue(ExistingStationMatch.matches("bergamot", "mini3", mapOf("MODEL" to "Yandex station-mini3")))
    }
    @Test fun station2HasItsOwnPlatform() {
        assertTrue(ExistingStationMatch.matches("yandexmidi", "station2", mapOf("PLATFORM" to "yandexmidi")))
        assertTrue(ExistingStationMatch.matches("yandexmidi", "station2", mapOf("MODEL" to "Yandex Station Midi")))
        assertFalse(ExistingStationMatch.matches("yandexmidi", "station2", mapOf("PLATFORM" to "yandexstation_2")))
        assertFalse(ExistingStationMatch.matches("yandexmidi", "station2", mapOf("PLATFORM" to "cucumber")))
    }
    @Test fun generationsAreNotInterchangeable() {
        assertFalse(ExistingStationMatch.matches("yandexmini", "mini1", mapOf("BUILDNAME" to "yandexmini_2")))
        assertFalse(ExistingStationMatch.matches("yandexstation", "station1", mapOf("PLATFORM" to "yandexstation_2")))
    }
    @Test fun streetMangoIsNotMidiOrStation2() {
        assertTrue(ExistingStationMatch.matches("mango", "street", mapOf("MODEL" to "Yandex Station Mango DVT")))
        assertTrue(ExistingStationMatch.matches("mango", "street", mapOf("PLATFORM" to "mango")))
        assertFalse(ExistingStationMatch.matches("mango", "street", mapOf("PLATFORM" to "cucumber")))
        assertFalse(ExistingStationMatch.matches("cucumber", "midi", mapOf("PLATFORM" to "mango")))
    }
    @Test fun missingOrVersionOnlyDoesNotQualify() {
        assertFalse(ExistingStationMatch.matches("yandexmicro", "lite1", emptyMap()))
        assertFalse(ExistingStationMatch.matches("yandexmicro", "lite1", mapOf("VERSION" to "yandexmicro")))
    }
}
