package app.pocketfoundry.core.drivers

import app.pocketfoundry.core.drivers.DriverCheck.Found
import app.pocketfoundry.core.drivers.DriverCheck.Result
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DriverCheckTest {
    private val eds1 = "VK_EXT_extended_dynamic_state"
    private val eds2 = "VK_EXT_extended_dynamic_state2"
    /** What the check turns off, try by try: Mesa's names, which reach Zink through ZINK_HIDE_EXTENSIONS. */
    private val first = listOf("VK_EXT_extended_dynamic_state3", "VK_EXT_color_write_enable",
                               "VK_EXT_vertex_input_dynamic_state")
    private val everyTry = listOf(emptyList(), first, first + eds2, first + eds2 + eds1)

    @Test fun `the first try turns nothing off and each next one turns off more`() {
        val tried = mutableListOf<List<String>>()
        DriverCheck.search { tried += it; Result.WRONG }
        assertEquals(everyTry, tried)
    }

    @Test fun `a driver that draws right keeps everything, after one try`() {
        val tried = mutableListOf<List<String>>()
        assertEquals(Found.Right(emptyList()), DriverCheck.search { tried += it; Result.RIGHT })
        assertEquals(1, tried.size)
    }

    @Test fun `the first step that draws right is what gets turned off`() {
        // Like Arm's r38p1 driver: black until extended dynamic state itself is off.
        val found = DriverCheck.search { if (eds1 in it) Result.RIGHT else Result.WRONG }
        assertEquals(Found.Right(everyTry.last()), found)
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
        assertEquals(everyTry, tried)
    }

    @Test fun `a driver that crashes until extended dynamic state is off runs with it off`() {
        // Like a Mali-G72's driver: the check crashes with extended dynamic state 1 and 2 on.
        val found = DriverCheck.search { if (eds1 in it) Result.RIGHT else Result.FAILED }
        assertEquals(Found.Right(everyTry.last()), found)
    }

    @Test fun `a check that crashes whatever is off couldn't draw`() {
        val tried = mutableListOf<List<String>>()
        assertEquals(Found.Failed, DriverCheck.search { tried += it; Result.FAILED })
        assertEquals(everyTry, tried)
    }

    @Test fun `a check that drew wrong once and crashed after is wrong`() {
        assertEquals(Found.Wrong, DriverCheck.search { if (it.isEmpty()) Result.WRONG else Result.FAILED })
    }

    @Test fun `a check that hangs stops there`() {
        val tried = mutableListOf<List<String>>()
        assertEquals(Found.Failed, DriverCheck.search { tried += it; if (it.isEmpty()) Result.WRONG else Result.HUNG })
        assertEquals(2, tried.size)
    }

    @Test fun `a driver that draws right as it is says so`() {
        assertEquals("the test picture is right", DriverCheck.summary(JSONObject().put("off", "").put("transcode", false)))
    }

    @Test fun `the summary names what's off and whether textures stay compressed`() {
        val saved = JSONObject().put("off", "$eds2,$eds1").put("transcode", true).put("failed", false)
        assertEquals("$eds2, $eds1 off · compressed textures kept compressed", DriverCheck.summary(saved))
    }

    @Test fun `a driver wrong whatever is off says so`() {
        val saved = JSONObject().put("off", "").put("transcode", false).put("failed", false).put("wrong", true)
        assertEquals("the test picture is wrong whatever is off", DriverCheck.summary(saved))
    }

    @Test fun `a driver that needed Khronos's timeline semaphores says so`() {
        val saved = JSONObject().put("off", "").put("transcode", true).put("timelines", true)
        assertEquals("timeline semaphores from Khronos's layer · compressed textures kept compressed",
                     DriverCheck.summary(saved))
    }

    @Test fun `a check that couldn't draw is remembered as that`() {
        val saved = JSONObject().put("off", "").put("transcode", false).put("failed", true)
        assertEquals("the test couldn't draw", DriverCheck.summary(saved))
    }

    @Test fun `what the check found goes over the game's environment`() {
        val environment = mapOf("VK_INSTANCE_LAYERS" to "VK_LAYER_HYBRIS_compat", "A" to "1")
        val found = JSONObject().put("timelines", true).put("off", "$eds2,$eds1").put("transcode", true)
        assertEquals(mapOf("VK_INSTANCE_LAYERS" to "VK_LAYER_KHRONOS_timeline_semaphore:VK_LAYER_HYBRIS_compat",
                           "A" to "1", "ZINK_HIDE_EXTENSIONS" to "$eds2,$eds1", "HYBRIS_BC_TEXTURES" to "1"),
                     DriverCheck.withFindings(environment, found))
    }

    @Test fun `a check that found nothing off leaves the environment as it is`() {
        val environment = mapOf("HYBRIS_BC_TEXTURES" to "0")
        assertEquals(environment, DriverCheck.withFindings(environment, JSONObject().put("off", "")))
    }

    @Test fun `the driver screen gets the check in a few words`() {
        assertEquals("Passed", DriverCheck.brief(JSONObject().put("off", "").put("transcode", false)))
        assertEquals("Passed · 2 features off · textures kept compressed",
                     DriverCheck.brief(JSONObject().put("off", "$eds2,$eds1").put("transcode", true)))
        assertEquals("Passed · 1 feature off", DriverCheck.brief(JSONObject().put("off", eds1)))
        assertEquals("Wrong picture", DriverCheck.brief(JSONObject().put("off", "").put("wrong", true)))
        assertEquals("Couldn't draw", DriverCheck.brief(JSONObject().put("off", "").put("failed", true)))
    }
}
