package com.mobiledivecontrol.core

import kotlin.test.*
import kotlin.math.*

class BuhlmannTest {
    @Test fun `all tissues ceilings and NDL match independent DecoTengu profiles`() {
        val lines = javaClass.getResourceAsStream("/decompression/decotengu-0.14.1.txt")!!.bufferedReader().readLines()
        var cases = 0
        for (line in lines.filter { it.isNotBlank() && !it.startsWith("#") }) {
            val f = line.split('|')
            var tissues = Buhlmann.equilibrium()
            var depth = 0.0
            var gas = DiveGas()
            for (segment in f[1].split(';')) {
                val s = segment.split(',')
                val end = s[0].toDouble()
                gas = DiveGas(s[2].toInt(), s[3].toInt())
                tissues = Buhlmann.load(tissues, Buhlmann.pressure(depth), Buhlmann.pressure(end), s[1].toDouble(), gas)
                depth = end
            }
            val n = f[2].split(',').map(String::toDouble)
            val h = f[3].split(',').map(String::toDouble)
            for (i in 0..15) {
                assertEquals(n[i], tissues[i].nitrogenBar, 1e-9, "${f[0]} N2 tissue $i")
                assertEquals(h[i], tissues[i].heliumBar, 1e-9, "${f[0]} He tissue $i")
            }
            assertEquals(f[4].toDouble(), Buhlmann.ceilingPressure(tissues, .4), 1e-9, f[0])
            assertEquals(f[5].toDouble(), Buhlmann.ceilingPressure(tissues, .85), 1e-9, f[0])
            assertEquals(f[6].toInt(), Buhlmann.ndlSeconds(tissues, depth, gas), f[0])
            cases++
        }
        assertEquals(10, cases)
    }

    @Test fun `integration is independent of sample subdivision including pressure ramps`() {
        val initial = Buhlmann.equilibrium()
        val gas = DiveGas(21, 35)
        val one = Buhlmann.load(initial, Buhlmann.pressure(0.0), Buhlmann.pressure(50.0), 2.5, gas)
        var divided = initial
        repeat(600) { i -> divided = Buhlmann.load(divided,
            Buhlmann.pressure(50.0*i/600), Buhlmann.pressure(50.0*(i+1)/600), 2.5/600, gas) }
        for (i in 0..15) {
            assertEquals(one[i].nitrogenBar, divided[i].nitrogenBar, 1e-10)
            assertEquals(one[i].heliumBar, divided[i].heliumBar, 1e-10)
        }
    }

    @Test fun `GF ceiling is the boundary between allowed and disallowed ambient pressure`() {
        val tissues = Buhlmann.load(Buhlmann.equilibrium(), Buhlmann.pressure(40.0), Buhlmann.pressure(40.0), 35.0, DiveGas())
        val anchor = ceil(Buhlmann.depth(Buhlmann.ceilingPressure(tissues, Buhlmann.GF_LOW))/3)*3
        val ceiling = Buhlmann.ceilingMeters(tissues, anchor)
        assertTrue(ceiling > 0)
        fun margin(depth: Double): Double {
            val gf = Buhlmann.GF_HIGH + (Buhlmann.GF_LOW-Buhlmann.GF_HIGH)*(depth/anchor).coerceIn(0.0,1.0)
            return Buhlmann.pressure(depth) - Buhlmann.ceilingPressure(tissues, gf)
        }
        assertTrue(margin(ceiling + .001) > 0)
        assertTrue(margin(ceiling - .001) < 0)
    }

    @Test fun `NOAA clock OTU units and surface recovery match reference values`() {
        assertEquals(100.0, OxygenExposure.cnsPercentPerMinute(1.4) * 150, 1e-10)
        assertEquals(100.0, OxygenExposure.cnsPercentPerMinute(1.6) * 45, 1e-10)
        assertEquals(0.0, OxygenExposure.otuPerMinute(.5))
        assertEquals(1.0, OxygenExposure.otuPerMinute(1.0), 1e-10)
        assertEquals(50.0, OxygenExposure.surfaceCns(100.0, 90.0), 1e-10)
        assertEquals(.32, OxygenExposure.ppO2(1.01325, DiveGas(32)), 1e-10)
    }
}
