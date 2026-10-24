package app.radiusrf.features.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveillanceMapTest {

    @Test
    fun `parses OSM surveillance and ALPR nodes`() {
        val json = """
            {"elements":[
              {"type":"node","id":11,"lat":37.8,"lon":-122.4,"tags":{"man_made":"surveillance","name":"Lobby cam"}},
              {"type":"node","id":12,"lat":37.81,"lon":-122.41,"tags":{"highway":"speed_camera","name":"Bay Bridge"}},
              {"type":"node","id":13,"lat":1.0,"lon":2.0,"tags":{"amenity":"bench"}}
            ]}
        """.trimIndent()
        val sites = SurveillanceMap.parseOverpass(json)
        assertEquals(2, sites.size)
        assertEquals("Lobby cam", sites[0].name)
        assertEquals("Camera", sites[0].kind)
        assertEquals("ALPR / speed", sites[1].kind)
    }

    @Test
    fun `query includes the bounding box and camera tags`() {
        val q = SurveillanceMap.query(1.0, 2.0, 3.0, 4.0)
        assertTrue(q.contains("man_made"))
        assertTrue(q.contains("speed_camera"))
        assertTrue(q.contains("1.0,2.0,3.0,4.0"))
    }
}
