package app.pocketfoundry.core.drivers

import app.pocketfoundry.core.drivers.DriverCheck.Found
import app.pocketfoundry.core.drivers.DriverCheck.Result
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DriverCheckTest {
    private val eds1 = "VK_EXT_extended_dynamic_state"
    private val eds2 = "VK_EXT_extended_dynamic_state2"

    @Test fun `the first try turns nothing off and each next one turns off more`() {
        assertEquals(emptyList<String>(), DriverCheck.steps.first())
        DriverCheck.steps.zipWithNext().forEach { (before, after) -> assertEquals(before, after.take(before.size)) }
        assertEquals(eds1, DriverCheck.steps.last().last())
    }

    @Test fun `a driver that draws right keeps everything, after one try`() {
        val tried = mutableListOf<List<String>>()
        assertEquals(Found.Right(emptyList()), DriverCheck.search { tried += it; Result.RIGHT })
        assertEquals(1, tried.size)
    }

    @Test fun `the first step that draws right is what gets turned off`() {
        // Like Arm's r38p1 driver: black until extended dynamic state itself is off.
        val found = DriverCheck.search { if (eds1 in it) Result.RIGHT else Result.WRONG }
        assertEquals(Found.Right(DriverCheck.steps.last()), found)
    }

    @Test fun `it stops at the step that fixes it`() {
        val tried = mutableListOf<List<String>>()
        val found = DriverCheck.search { tried += it; if (eds2 in it) Result.RIGHT else Result.WRONG }
        assertEquals(Found.Right(tried.last()), found)
        assertEquals(false, eds1 in tried.last())
    }

    @Test fun `wrong whatever is off`() {
        val tried = mutableListOf<List<String>>()
        assertEquals(Found.Wrong, DriverCheck.search { tried += it; Result.WRONG })
        assertEquals(DriverCheck.steps, tried)
    }

    @Test fun `a check that can't draw stops there`() {
        val tried = mutableListOf<List<String>>()
        assertEquals(Found.Failed, DriverCheck.search { tried += it; if (it.isEmpty()) Result.WRONG else Result.FAILED })
        assertEquals(2, tried.size)
    }

    @Test fun `a driver that draws right as it is changes nothing`() {
        assertEquals("", DriverCheck.summary(JSONObject().put("off", "").put("transcode", false)))
    }

    @Test fun `the summary names what's off and whether textures stay compressed`() {
        val saved = JSONObject().put("off", "$eds2,$eds1").put("transcode", true).put("failed", false)
        assertEquals("$eds2, $eds1 off · compressed textures kept compressed", DriverCheck.summary(saved))
    }

    @Test fun `a check that couldn't draw is remembered as that`() {
        val saved = JSONObject().put("off", "").put("transcode", false).put("failed", true)
        assertEquals("the test couldn't draw", DriverCheck.summary(saved))
    }
}
