package com.moto.speedalert

import java.nio.ByteBuffer
import kotlin.math.floor

/**
 * Offline Taiwan road database (roads.bin, built by tools/build_roads.py), with a grid
 * index so the roads near the rider can be found instantly. No network needed.
 */
class RoadDb private constructor(
    val roads: List<Road>,
    private val grid: HashMap<Long, Array<Road>>,
) {
    /** Roads that pass within ~30 m of the point's grid cell. */
    fun near(lat: Double, lon: Double): List<Road> =
        grid[cellKey(cellOf(lat), cellOf(lon))]?.asList() ?: emptyList()

    companion object {
        private const val CELL = 0.005 // degrees, ~550 m
        private const val PAD = 0.0004 // ~40 m: a road near a cell edge is listed in both cells

        private fun cellOf(v: Double) = floor(v / CELL).toInt()
        private fun cellKey(cy: Int, cx: Int) = (cy.toLong() shl 32) or (cx.toLong() and 0xffffffffL)

        fun parse(bytes: ByteArray): RoadDb {
            val buf = ByteBuffer.wrap(bytes)
            val magic = ByteArray(4).also { buf.get(it) }
            require(String(magic, Charsets.US_ASCII) == "RDS1") { "bad roads.bin" }

            val names = Array(buf.int) {
                val b = ByteArray(buf.short.toInt() and 0xffff)
                buf.get(b)
                String(b, Charsets.UTF_8)
            }
            val count = buf.int
            val roads = ArrayList<Road>(count)
            val cells = HashMap<Long, ArrayList<Road>>(count)

            for (i in 0 until count) {
                val ni = buf.int
                val oneway = when (buf.get().toInt()) {
                    1 -> 1
                    2 -> -1
                    else -> 0
                }
                val ms = buf.get().toInt() and 0xff
                val fw = buf.get().toInt() and 0xff
                val bw = buf.get().toInt() and 0xff
                val n = buf.short.toInt() and 0xffff
                val lats = DoubleArray(n)
                val lons = DoubleArray(n)
                var la = buf.int
                var lo = buf.int
                lats[0] = la / 1e6
                lons[0] = lo / 1e6
                for (j in 1 until n) {
                    la += readZigzag(buf)
                    lo += readZigzag(buf)
                    lats[j] = la / 1e6
                    lons[j] = lo / 1e6
                }
                val road = Road(
                    id = i.toLong(),
                    name = if (ni >= 0) names[ni] else null,
                    highway = "",
                    oneway = oneway,
                    maxspeed = ms.takeIf { it > 0 },
                    maxspeedForward = fw.takeIf { it > 0 },
                    maxspeedBackward = bw.takeIf { it > 0 },
                    lats = lats,
                    lons = lons,
                )
                roads += road
                // Register each segment in every cell its padded bounding box touches.
                val seen = HashSet<Long>()
                for (j in 0 until n - 1) {
                    val y0 = cellOf(minOf(lats[j], lats[j + 1]) - PAD)
                    val y1 = cellOf(maxOf(lats[j], lats[j + 1]) + PAD)
                    val x0 = cellOf(minOf(lons[j], lons[j + 1]) - PAD)
                    val x1 = cellOf(maxOf(lons[j], lons[j + 1]) + PAD)
                    for (cy in y0..y1) for (cx in x0..x1) {
                        val k = cellKey(cy, cx)
                        if (seen.add(k)) cells.getOrPut(k) { ArrayList(4) } += road
                    }
                }
            }
            val grid = HashMap<Long, Array<Road>>(cells.size)
            for ((k, v) in cells) grid[k] = v.toTypedArray()
            return RoadDb(roads, grid)
        }

        private fun readZigzag(buf: ByteBuffer): Int {
            var result = 0L
            var shift = 0
            while (true) {
                val b = buf.get().toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            return ((result ushr 1) xor -(result and 1)).toInt()
        }
    }
}
