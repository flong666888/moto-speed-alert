package com.moto.speedalert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** sample_roads.bin is built by tools/build_roads.py from a small hand-made OSM file. */
class RoadDbTest {
    private val db = RoadDb.parse(javaClass.getResourceAsStream("/sample_roads.bin")!!.readBytes())

    private fun limitAt(lat: Double, lon: Double, heading: Double): Int? =
        Roads.match(db.near(lat, lon), lat, lon, heading)?.limit

    @Test
    fun keepsOnlyUsefulRoads() {
        // residential without a limit and service roads are dropped by the converter
        assertEquals(4, db.roads.size)
        assertTrue(db.roads.none { it.name == "無限速巷" })
    }

    @Test
    fun matchesFromOfflineData() {
        assertEquals(60, limitAt(25.005, 121.50001, 2.0)) // 南北大道 northbound at the crossing
        assertEquals(30, limitAt(25.005, 121.50001, 88.0)) // 東西小路 eastbound at the same spot
        assertEquals(40, limitAt(25.0201, 121.505, 90.0)) // motorcycle-specific limit
        assertEquals(70, limitAt(25.035, 121.50001, 0.0)) // maxspeed:forward
        assertEquals(50, limitAt(25.035, 121.50001, 180.0)) // maxspeed:backward
        assertNull(limitAt(25.2, 121.7, 0.0)) // nothing nearby
    }

    @Test
    fun findsRoadNearGridCellEdge() {
        // 25.010 is exactly on a 0.005-degree cell boundary; the road must be found from both sides.
        assertEquals(60, limitAt(25.0099, 121.50002, 0.0))
        assertEquals(60, limitAt(25.0100, 121.50002, 0.0))
    }
}
