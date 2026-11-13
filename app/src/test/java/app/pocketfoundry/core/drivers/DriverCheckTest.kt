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

    @Test fun `no OpenGL at all stops at the first try, since fewer features can't give it`() {
        val tried = mutableListOf<List<String>>()
        assertEquals(Found.NoOpenGL, DriverCheck.search { tried += it; Result.NO_OPENGL })
        assertEquals(listOf(emptyList<String>()), tried)
    }

    @Test fun `a check that drew wrong before losing OpenGL is wrong`() {
        assertEquals(Found.Wrong, DriverCheck.search { if (it.isEmpty()) Result.WRONG else Result.NO_OPENGL })
    }

    @Test fun `a check that hangs stops there`() {
        val tried = mutableListOf<List<String>>()
        assertEquals(Found.Failed, DriverCheck.search { tried += it; if (it.isEmpty()) Result.WRONG else Result.HUNG })
        assertEquals(2, tried.size)
    }

    private fun saved(vararg pairs: Pair<String, Any>) = JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }
    private val plays = arrayOf("off" to "", "textures" to "driver", "cache" to true, "gpuCompression" to true)

    @Test fun `a driver that draws right as it is says so`() {
        assertEquals("the test picture is right", DriverCheck.summary(saved(*plays)))
    }

    @Test fun `the summary names what's off`() {
        assertEquals("$eds2, $eds1 off", DriverCheck.summary(saved(*plays, "off" to "$eds2,$eds1")))
    }

    @Test fun `a driver wrong whatever is off says so`() {
        assertEquals("the test picture is wrong whatever is off",
                     DriverCheck.summary(saved("off" to "", "failed" to false, "wrong" to true)))
    }

    @Test fun `a driver that failed says what its first try got wrong`() {
        val run = """
            drivercheck: zink Vulkan 1.4(Mali-G615 MC2 (MESA_PANVK))
            drivercheck: memory 5571 MB, 5564 MB available
            drivercheck: the BC1 square is 237,237,67 instead of 255,255,0 at texel 0,0
            drivercheck: the BC3 square is 237,67,237 instead of 255,0,255 at texel 0,0
            drivercheck: the picture is wrong""".trimIndent()
        val why = DriverCheck.why(run)
        assertEquals("the BC1 square is 237,237,67 instead of 255,255,0 at texel 0,0; " +
                         "the BC3 square is 237,67,237 instead of 255,0,255 at texel 0,0", why)
        assertEquals("the test picture is wrong whatever is off · first try: $why",
                     DriverCheck.summary(saved("off" to "", "wrong" to true, "why" to why)))
    }

    @Test fun `the formats the driver samples come in a few words`() {
        assertEquals("Vulkan 1.4, samples ETC2, EAC, ASTC", DriverCheck.formats(
            "drivercheck: formats Vulkan 1.4 (Mali-G615 MC2): BC no, ETC2 yes, EAC yes, ASTC yes"))
        assertEquals("Vulkan 1.3, samples some BC, ASTC", DriverCheck.formats(
            "drivercheck: formats Vulkan 1.3 (Mali (x)): BC some, ETC2 no, EAC no, ASTC yes"))
        assertEquals(null, DriverCheck.formats("drivercheck: formats unknown: no Vulkan device"))
    }

    @Test fun `a check that crashed says where`() {
        assertEquals("crashed with signal 11, touching 0x0; in /vendor/lib64/hw/vulkan.mali.so+0x1234",
                     DriverCheck.why("drivercheck: crashed with signal 11, touching 0x0\n" +
                                         "drivercheck: in /vendor/lib64/hw/vulkan.mali.so+0x1234\n"))
    }

    @Test fun `a driver that needed help to draw says which`() {
        assertEquals("OpenGL 3.3 asked for · timeline semaphores from Khronos's layer",
                     DriverCheck.summary(saved(*plays, "timelines" to true, "openGL33" to true)))
    }

    @Test fun `a check that couldn't draw is remembered as that`() {
        assertEquals("the test couldn't draw", DriverCheck.summary(saved("off" to "", "failed" to true)))
    }

    @Test fun `a driver that gives no OpenGL says so`() {
        val saved = saved("off" to "", "failed" to true, "noOpenGL" to true)
        assertEquals("Zink got no OpenGL from the driver", DriverCheck.summary(saved))
        assertEquals("No OpenGL", DriverCheck.brief(saved))
    }

    @Test fun `what the check found goes over the game's environment`() {
        val environment = mapOf("VK_INSTANCE_LAYERS" to "VK_LAYER_HYBRIS_compat", "A" to "1")
        val found = saved("timelines" to true, "off" to "$eds2,$eds1", "textures" to "layer")
        assertEquals(mapOf("VK_INSTANCE_LAYERS" to "VK_LAYER_KHRONOS_timeline_semaphore:VK_LAYER_HYBRIS_compat",
                           "A" to "1", "ZINK_HIDE_EXTENSIONS" to "$eds2,$eds1", "HYBRIS_BC_TEXTURES" to "1"),
                     DriverCheck.withFindings(environment, found) { way, _ ->
                         if (way == "layer") mapOf("HYBRIS_BC_TEXTURES" to "1") else emptyMap()
                     })
    }

    @Test fun `the textures' way goes over what else the check found`() {
        val found = saved("off" to eds1, "textures" to "layer")
        assertEquals(mapOf("ZINK_HIDE_EXTENSIONS" to eds1, "SEEN" to eds1),
                     DriverCheck.withFindings(emptyMap(), found) { _, over ->
                         mapOf("SEEN" to over[DriverCheck.HIDE]!!)
                     })
    }

    @Test fun `OpenGL 3_3 asked for keeps the Options' own extension overrides`() {
        val found = saved("off" to "", "openGL33" to true)
        assertEquals(mapOf("MESA_EXTENSION_OVERRIDE" to "-GL_X +GL_ARB_instanced_arrays",
                           "MESA_GL_VERSION_OVERRIDE" to "3.3"),
                     DriverCheck.withFindings(mapOf("MESA_EXTENSION_OVERRIDE" to "-GL_X"), found) { _, _ ->
                         emptyMap()
                     })
    }

    @Test fun `the faster readback the speed test found goes to the game`() {
        val found = saved("off" to "", "textures" to "driver", "swReadback" to "0")
        assertEquals(mapOf("A" to "1", "PANVK_WSI_SW_CACHED" to "0"),
                     DriverCheck.withFindings(mapOf("A" to "1"), found) { _, _ -> emptyMap() })
        assertEquals("frames read back through uncached memory",
                     DriverCheck.summary(saved(*plays, "swReadback" to "0")))
    }

    @Test fun `a check that found nothing leaves the environment as it is`() {
        val environment = mapOf("HYBRIS_BC_TEXTURES" to "0")
        assertEquals(environment,
                     DriverCheck.withFindings(environment, saved("off" to "")) { _, _ -> mapOf("X" to "1") })
    }

    @Test fun `a driver that can't keep the textures compressed can't play`() {
        val saved = saved("off" to "", "textures" to "none", "tried" to "layer: read wrong")
        assertEquals(false, DriverCheck.passed(saved))
        assertEquals("Textures don't stay compressed", DriverCheck.brief(saved))
        assertEquals("the game's textures don't stay compressed any way (layer: read wrong)",
                     DriverCheck.summary(saved))
    }

    @Test fun `atlases that come back wrong turn fast start off, and wrong copies the GPU compressor`() {
        val saved = saved(*plays, "textures" to "layer", "cache" to false, "gpuCompression" to false)
        assertEquals("textures through the compat layer · fast start off: the atlases come back wrong · " +
                         "the CPU compresses: copied blocks read wrong", DriverCheck.summary(saved))
        assertEquals("Passed · fast start off", DriverCheck.brief(saved))
    }

    @Test fun `the driver's memory comes after what the picture showed`() {
        assertEquals("the test picture is right · GPU memory 2322 MB, 1980 free",
                     DriverCheck.summary(saved(*plays, "memory" to 2322, "available" to 1980)))
    }

    @Test fun `only a driver that drew right and keeps the textures compressed passes`() {
        assertEquals(true, DriverCheck.passed(saved(*plays, "off" to eds1, "cache" to false)))
        assertEquals(false, DriverCheck.passed(saved("wrong" to true)))
        assertEquals(false, DriverCheck.passed(saved("failed" to true, "noOpenGL" to true)))
    }

    @Test fun `the driver screen gets the check in a few words`() {
        assertEquals("Passed", DriverCheck.brief(saved(*plays)))
        assertEquals("Passed · 2 features off", DriverCheck.brief(saved(*plays, "off" to "$eds2,$eds1")))
        assertEquals("Passed · 1 feature off", DriverCheck.brief(saved(*plays, "off" to eds1)))
        assertEquals("Wrong picture", DriverCheck.brief(saved("off" to "", "wrong" to true)))
        assertEquals("Couldn't draw", DriverCheck.brief(saved("off" to "", "failed" to true)))
    }

    private val driverWay = Drivers.TextureWay(Drivers.TextureWay.DRIVER, emptyMap(), convertsWhatsMissing = false)
    private val layerWay = Drivers.TextureWay(Drivers.TextureWay.LAYER, emptyMap(), convertsWhatsMissing = true)
    private fun atlases(draws: Boolean = true, comesBack: Boolean = true, takes: Int = 66) =
        DriverCheck.Atlases(draws, comesBack, copies = true, takesMb = takes, compressedMb = 64)

    @Test fun `an atlas that took up to twice its compressed size stayed compressed, four times that didn't`() {
        assertEquals(true, atlases(takes = 66).compressed)
        // The compat layer's ASTC and the original blocks beside it, as on a Mali's own driver.
        assertEquals(true, atlases(takes = 131).compressed)
        assertEquals(false, atlases(takes = 256).compressed)
        assertEquals(null, atlases(takes = -1).compressed)
    }

    @Test fun `a way that draws wrong or unpacks can't be used`() {
        assertEquals(null, DriverCheck.rank(driverWay, atlases(draws = false)))
        assertEquals(null, DriverCheck.rank(layerWay, atlases(takes = 256)))
    }

    @Test fun `known compressed beats unknown, and coming back right beats not`() {
        assertEquals(3, DriverCheck.rank(driverWay, atlases()))
        assertEquals(2, DriverCheck.rank(driverWay, atlases(comesBack = false)))
        assertEquals(1, DriverCheck.rank(driverWay, atlases(takes = -1)))
    }

    @Test fun `where the driver doesn't say its memory, the converter counts as compressed`() {
        assertEquals(3, DriverCheck.rank(layerWay, atlases(takes = -1)))
        assertEquals(2, DriverCheck.rank(layerWay, atlases(takes = -1, comesBack = false)))
    }
}
