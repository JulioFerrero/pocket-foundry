package app.pocketfoundry

import org.junit.Assert.assertEquals
import org.junit.Test

class ResolutionScaleTest {
    @Test fun `the natural scale is a third of the dpi`() {
        assertEquals(160f, ResolutionScale.natural(480), 0f)
    }

    @Test fun `the default rounds the natural scale`() {
        assertEquals(160, ResolutionScale.default(480))
        assertEquals(187, ResolutionScale.default(560))
        assertEquals(115, ResolutionScale.default(344))
    }

    @Test fun `a default under 115 is native`() {
        assertEquals(100, ResolutionScale.default(343))
        assertEquals(100, ResolutionScale.default(200))
    }

    @Test fun `native, 125 and 150 are offered only 15 or more under the default`() {
        assertEquals(listOf(100, 125, 160, 240, 320), ResolutionScale.options(160))
        assertEquals(listOf(100, 125, 150, 165, 247, 330), ResolutionScale.options(165))
        assertEquals(listOf(100, 115, 172, 230), ResolutionScale.options(115))
    }

    @Test fun `a native default offers only native and its multiples`() {
        assertEquals(listOf(100, 150, 200), ResolutionScale.options(100))
    }

    @Test fun `the CPU driver's scale is twice the default`() {
        assertEquals(320, ResolutionScale.cpu(160))
    }

    @Test fun `nearest picks the closest option, the first on a tie`() {
        assertEquals(125, ResolutionScale.nearest(listOf(100, 125, 160), 140))
        assertEquals(100, ResolutionScale.nearest(listOf(100, 150), 125))
        assertEquals(320, ResolutionScale.nearest(listOf(100, 160, 320), 1000))
    }

    @Test fun `the UI scale follows the resolution scale`() {
        assertEquals(1f, ResolutionScale.uiScale(480, 160), 1e-6f)
        assertEquals(0.5f, ResolutionScale.uiScale(480, 320), 1e-6f)
    }

    @Test fun `the UI scale stops at SDL's lowest`() {
        assertEquals(0.25f, ResolutionScale.uiScale(480, 1000), 0f)
    }
}
