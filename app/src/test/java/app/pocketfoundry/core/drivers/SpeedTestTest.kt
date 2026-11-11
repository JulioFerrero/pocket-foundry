package app.pocketfoundry.core.drivers

import app.pocketfoundry.core.drivers.SpeedTest.Measured
import app.pocketfoundry.core.drivers.SpeedTest.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTestTest {
    /** A Galaxy S10's game screen at its default resolution, and at the coarsest. */
    private val fine = 1624 to 770
    private val coarse = 812 to 385
    private fun pixels(size: Pair<Int, Int>) = size.first.toLong() * size.second
    /** Scale to pixels, finest first, as the phone offers them. */
    private val sizes = listOf(187 to pixels(fine), 280 to pixels(1083 to 514), 374 to pixels(coarse))

    @Test fun `the program's lines give each size's time per frame, from the last run only`() {
        val log = """
            ── speed test
            speedtest: 1624x770 10 frames in 1000 ms
            ── off: nothing
            ── speed test
            speedtest: Mali-G76 MC12 (MESA_PANVK)
            speedtest: 1624x770 30 frames in 1500 ms
            speedtest: 812x385 100 frames in 1500 ms
        """.trimIndent()
        val measured = SpeedTest.parse(log)
        assertEquals(listOf(1624 to 770, 812 to 385), measured.map { it.width to it.height })
        assertEquals(listOf(50.0, 15.0), measured.map { it.ms })
    }

    @Test fun `a run that drew nothing measured nothing`() {
        assertEquals(emptyList<Measured>(), SpeedTest.parse("── speed test\nspeedtest: no OpenGL 3.3 context\n"))
        assertNull(SpeedTest.model(emptyList()))
    }

    @Test fun `two sizes split a frame into a fixed part and a part per pixel`() {
        // 4 ms whatever the size, 10 ms per million pixels.
        val measured = listOf(Measured(1000, 1000, 14.0), Measured(500, 500, 6.5))
        val model = SpeedTest.model(measured)!!
        assertEquals(4.0, model.fixedMs, 1e-9)
        assertEquals(10.0, model.msPerMegapixel, 1e-9)
    }

    @Test fun `a coarser frame that took longer counts as all fixed`() {
        val model = SpeedTest.model(listOf(Measured(1000, 1000, 10.0), Measured(500, 500, 12.0)))!!
        assertEquals(0.0, model.msPerMegapixel, 1e-9)
        assertEquals(10.0, model.fixedMs, 1e-9)
    }

    @Test fun `one size puts all of its time on the pixels`() {
        val model = SpeedTest.model(listOf(Measured(1000, 1000, 20.0)))!!
        assertEquals(0.0, model.fixedMs, 1e-9)
        assertEquals(20.0, model.msPerMegapixel, 1e-9)
    }

    @Test fun `a fast driver keeps the default resolution`() {
        val fit = SpeedTest.fit(Model(1.0, 2.0), sizes, target = 150.0)
        assertEquals(187, fit.scale)
        assertFalse(fit.light)
    }

    @Test fun `a slower one gets the finest resolution that keeps the frame rate`() {
        // 75 FPS needed: 13.3 ms. Default 1.25 MP: 3 + 12.5 = 15.5 ms; next 0.56 MP: 3 + 5.6 = 8.6 ms.
        val fit = SpeedTest.fit(Model(3.0, 10.0), sizes, target = 75.0)
        assertEquals(280, fit.scale)
        assertFalse(fit.light)
    }

    @Test fun `too slow everywhere gets the coarsest and Light graphics`() {
        val fit = SpeedTest.fit(Model(20.0, 40.0), sizes, target = 75.0)
        assertEquals(374, fit.scale)
        assertTrue(fit.light)
    }

    @Test fun `half the frames needed keeps a finer resolution`() {
        val model = Model(3.0, 10.0)
        assertEquals(280, SpeedTest.fit(model, sizes, target = 75.0).scale)
        assertEquals(187, SpeedTest.fit(model, sizes, target = 37.5).scale)
    }

    @Test fun `what's measured is kept and read back alike`() {
        val measured = listOf(Measured(1624, 770, 23.1), Measured(812, 385, 9.0))
        val read = SpeedTest.fromJson(SpeedTest.toJson(measured))
        fun Measured.triple() = Triple(width, height, ms)
        assertEquals(measured.map { it.triple() }, read.map { it.triple() })
        assertEquals("23.1 ms a frame at 1624x770, 9.0 at 812x385", SpeedTest.summary(read))
    }
}
