package com.marcusrosado.stamp

import com.marcusrosado.stamp.data.LegPath
import com.marcusrosado.stamp.data.RouteNet
import com.marcusrosado.stamp.data.Travel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class RouteNetTest {
    /** A pack as tools/build_routes.py writes it, from junctions and segments in degrees. */
    private fun pack(nodes: List<Pair<Double, Double>>, segs: List<Triple<Int, Int, Pair<Int, List<Pair<Double, Double>>>>>): ByteArray {
        val out = ByteArrayOutputStream()
        fun varint(v: Long) { var n = v; while (true) { val b = (n and 0x7F).toInt(); n = n ushr 7; if (n != 0L) out.write(b or 0x80) else { out.write(b); return } } }
        fun zz(v: Int) = varint(((v shl 1) xor (v shr 31)).toLong() and 0xFFFFFFFFL)
        fun e5(d: Double) = Math.round(d * 1e5).toInt()
        out.write("SRT1".toByteArray())
        varint(nodes.size.toLong())
        var py = 0; var px = 0
        for ((la, lo) in nodes) { zz(e5(la) - py); zz(e5(lo) - px); py = e5(la); px = e5(lo) }
        varint(segs.size.toLong())
        for ((a, b, rest) in segs) {
            val (kind, pts) = rest
            out.write(kind)
            varint(a.toLong()); varint(b.toLong()); varint(pts.size.toLong())
            py = e5(nodes[a].first); px = e5(nodes[a].second)
            for ((la, lo) in pts) { zz(e5(la) - py); zz(e5(lo) - px); py = e5(la); px = e5(lo) }
        }
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(out.toByteArray()) }
        return gz.toByteArray()
    }

    // A rail line that bends north between two towns, a road straight across, and a rail line
    // on an island nothing joins.
    private val net = RouteNet.read(
        pack(
            nodes = listOf(55.0 to 10.0, 55.0 to 11.0, 56.5 to 20.0, 56.5 to 20.5),
            segs = listOf(
                Triple(0, 1, 0 to listOf(55.3 to 10.5)),
                Triple(0, 1, 1 to emptyList()),
                Triple(2, 3, 0 to emptyList()),
            ),
        ),
    )

    @Test fun aTrainFollowsTheRailsBetweenTwoTowns() {
        val line = net.follow(Travel.Train, 55.01 to 10.0, 55.0 to 11.01)
        assertNotNull(line)
        // From the town, along the bend, to the other town.
        assertEquals(55.01 to 10.0, line!!.first())
        assertEquals(55.0 to 11.01, line.last())
        assertTrue(line.any { it.first > 55.2 })
    }

    @Test fun aCarKeepsToTheRoad() {
        val line = net.follow(Travel.Car, 55.0 to 10.0, 55.0 to 11.0)!!
        assertTrue(line.none { it.first > 55.2 })
    }

    @Test fun withNoLineJoiningThemTheLegStaysStraight() {
        assertNull(net.follow(Travel.Train, 55.0 to 10.0, 56.5 to 20.5))
        assertNull(net.follow(Travel.Walk, 55.0 to 10.0, 55.0 to 11.0))
        val parts = LegPath.parts(55.0 to 10.0, 56.5 to 20.5, listOf(Travel.Train), follow = { w, a, b -> net.follow(w, a, b) })
        assertEquals(listOf(55.0 to 10.0, 56.5 to 20.5), parts.single().first)
    }

    @Test fun twoCountriesJoinAtTheBorder() {
        val west = RouteNet.read(pack(listOf(50.0 to 5.0, 50.0 to 6.0), listOf(Triple(0, 1, 0 to emptyList()))))
        val east = RouteNet.read(pack(listOf(50.0 to 6.0, 50.0 to 7.0), listOf(Triple(0, 1, 0 to emptyList()))))
        val both = RouteNet.merge(listOf(west, east))
        assertNotNull(both.follow(Travel.Train, 50.0 to 5.0, 50.0 to 7.0))
        assertNull(west.follow(Travel.Train, 50.0 to 5.0, 50.0 to 7.0))
    }

    @Test fun aMergedNetworkKeepsEachSegmentsOwnPoints() {
        // Two countries, each with a bend; after the merge each bend is still where it was.
        val west = RouteNet.read(pack(listOf(50.0 to 5.0, 50.0 to 6.0), listOf(Triple(0, 1, 0 to listOf(50.4 to 5.5)))))
        val east = RouteNet.read(pack(listOf(50.0 to 6.0, 50.0 to 7.0), listOf(Triple(0, 1, 0 to listOf(49.6 to 6.5)))))
        val line = RouteNet.merge(listOf(west, east)).follow(Travel.Train, 50.0 to 5.0, 50.0 to 7.0)!!
        assertTrue(line.any { Math.abs(it.first - 50.4) < 1e-4 && Math.abs(it.second - 5.5) < 1e-4 })
        assertTrue(line.any { Math.abs(it.first - 49.6) < 1e-4 && Math.abs(it.second - 6.5) < 1e-4 })
    }

    @Test fun aLegThroughAThirdCountryUsesItsNetwork() {
        // A to C by rail only joins through B, whose network neither end lies in.
        val a = RouteNet.read(pack(listOf(50.0 to 5.0, 50.0 to 5.9), listOf(Triple(0, 1, 0 to emptyList()))))
        val b = RouteNet.read(pack(listOf(50.0 to 5.9, 50.0 to 7.1), listOf(Triple(0, 1, 0 to emptyList()))))
        val c = RouteNet.read(pack(listOf(50.0 to 7.1, 50.0 to 8.0), listOf(Triple(0, 1, 0 to emptyList()))))
        val lines = com.marcusrosado.stamp.data.RouteLines(mapOf("AA" to a, "BB" to b, "CC" to c))
        assertNotNull(lines.follow(Travel.Train, 50.0 to 5.0, 50.0 to 8.0))
    }

    @Test fun thinningKeepsTheShapeAndDropsTheRest() {
        val straight = (0..100).map { 55.0 to 10.0 + it * 0.01 }
        assertEquals(2, RouteNet.simplify(straight, 0.0015).size)
        val bent = straight.take(50) + (0..50).map { 55.0 + it * 0.01 to 10.5 }
        val thin = RouteNet.simplify(bent, 0.0015)
        assertTrue(thin.size in 3..4)
        assertEquals(bent.first(), thin.first())
        assertEquals(bent.last(), thin.last())
    }
}
