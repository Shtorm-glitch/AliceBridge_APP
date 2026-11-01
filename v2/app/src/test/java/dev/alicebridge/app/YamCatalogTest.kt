package dev.alicebridge.app

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class YamCatalogTest {
    @Test fun versionHistoryProducesOneCardAndOneMatchingInstallTarget() {
        val selected = YamCatalog.latest(JSONArray("""[
            {"id":"soloist-lite1","version":"1.0.4","url":"latest"},
            {"id":"soloist-lite1","version":"1.0.0","url":"old"},
            {"id":"station-airplay","version":"1.2.0"},
            {"id":"station-airplay","version":"1.10.0"},
            {"id":"station-airplay","version":"1.4.0"}
        ]"""))
        assertEquals(listOf("soloist-lite1", "station-airplay"), selected.map { it.getString("id") })
        assertEquals("latest", selected[0].getString("url"))
        assertEquals("1.10.0", selected[1].getString("version"))
    }
    @Test fun stableReleaseWinsOverPrereleaseAndEmptyIdsAreIgnored() {
        val selected = YamCatalog.latest(JSONArray("""[
            {"id":"tool","version":"2.0.0-rc.10"},
            {"id":"tool","version":"2.0.0"},
            {"id":"tool","version":"2.0.0-rc.11"},
            {"id":"","version":"3.0.0"}
        ]"""))
        assertEquals(1, selected.size)
        assertEquals("2.0.0", selected.single().getString("version"))
    }
}
