package app.pocketfoundry.core.drivers

import app.pocketfoundry.core.system.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.json.JSONObject
import java.io.File

class DriversTest {
    @Test fun `options are NAME=value pairs split by any whitespace`() {
        assertEquals(mapOf("TU_DEBUG" to "noconform", "MESA_VK_WSI_PRESENT_MODE" to "fifo", "ZINK_DEBUG" to "x"),
                     Drivers.parseOptions("TU_DEBUG=noconform\nMESA_VK_WSI_PRESENT_MODE=fifo\t  ZINK_DEBUG=x"))
    }

    @Test fun `empty text has no options`() {
        assertEquals(emptyMap<String, String>(), Drivers.parseOptions(""))
        assertEquals(emptyMap<String, String>(), Drivers.parseOptions("  \n\t "))
    }

    @Test fun `an empty value is kept`() {
        assertEquals(mapOf("A" to ""), Drivers.parseOptions("A="))
    }

    @Test fun `the value keeps later equals signs`() {
        assertEquals(mapOf("A" to "b=c"), Drivers.parseOptions("A=b=c"))
    }

    @Test fun `words that aren't NAME=value are dropped`() {
        assertEquals(mapOf("OK" to "1", "_x9" to "y"), Drivers.parseOptions("foo 1A=b =x A-B=c OK=1 _x9=y"))
    }

    @Test fun `the last of a repeated name wins`() {
        assertEquals(mapOf("A" to "2"), Drivers.parseOptions("A=1 A=2"))
    }

    @Test fun `quotes don't join words`() {
        assertEquals(mapOf("A" to "'x"), Drivers.parseOptions("A='x y'"))
    }

    @Test fun `the catalog's drivers for the GPU first, then the phone's own, then the CPU`() {
        assertEquals("turnip", Drivers.recommend(listOf("turnip", "freedreno"), vulkan = true) { false })
        assertEquals("phone", Drivers.recommend(emptyList(), vulkan = true) { false })
        assertEquals("cpu", Drivers.recommend(emptyList(), vulkan = false) { false })
    }

    @Test fun `a driver that didn't start is passed over`() {
        assertEquals("freedreno", Drivers.recommend(listOf("turnip", "freedreno"), vulkan = true) { it == "turnip" })
        assertEquals("phone", Drivers.recommend(listOf("turnip"), vulkan = true) { it == "turnip" })
        assertEquals("cpu", Drivers.recommend(emptyList(), vulkan = true) { it == "phone" })
        assertEquals("cpu", Drivers.recommend(listOf("turnip"), vulkan = true) { true })
    }

    private val paths = Paths(File("/files"), File("/lib"))

    private fun entry(id: String, kind: String, env: String = "{}") = Catalog.Entry(JSONObject("""
        {"id": "$id", "name": "$id", "kind": "$kind", "env": $env,
         "file": {"url": "https://example.com/$id.tar.xz", "sha256": "00", "size": 1}}"""))

    @Test fun `a catalog Vulkan driver draws through Zink on it, with its own settings and the fixes for it`() {
        val turnip = entry("turnip", "vulkan", """{"TU_DEBUG": "x"}""")
        val environment = Drivers.environment(paths, "turnip", turnip, mapOf("FD_DEV_FEATURES" to "y"))
        assertEquals("zink", environment["MESA_LOADER_DRIVER_OVERRIDE"])
        assertEquals(DriverImport.icd(paths, "turnip").path, environment["VK_DRIVER_FILES"])
        assertEquals("x", environment["TU_DEBUG"])
        assertEquals("y", environment["FD_DEV_FEATURES"])
        assertEquals(null, environment["MESA_VK_WSI_DEBUG"])
    }

    @Test fun `a catalog OpenGL driver is its own Mesa, without Zink`() {
        val freedreno = entry("freedreno", "opengl", """{"MESA_LOADER_DRIVER_OVERRIDE": "kgsl"}""")
        val environment = Drivers.environment(paths, "freedreno", freedreno, emptyMap())
        assertEquals("kgsl", environment["MESA_LOADER_DRIVER_OVERRIDE"])
        assertEquals(null, environment["VK_DRIVER_FILES"])
    }

    @Test fun `the CPU driver is llvmpipe for every way Mesa picks one`() {
        val environment = Drivers.environment(paths, "cpu", null, emptyMap())
        assertEquals("llvmpipe", environment["MESA_LOADER_DRIVER_OVERRIDE"])
        assertEquals("llvmpipe", environment["GALLIUM_DRIVER"])
        assertEquals("1", environment["LIBGL_ALWAYS_SOFTWARE"])
    }

    @Test fun `an imported driver draws through Zink on its own Vulkan driver, presenting in software`() {
        val environment = Drivers.environment(paths, "my-turnip", null, emptyMap())
        assertEquals("zink", environment["MESA_LOADER_DRIVER_OVERRIDE"])
        assertEquals(DriverImport.icd(paths, "my-turnip").path, environment["VK_DRIVER_FILES"])
        assertEquals("sw", environment["MESA_VK_WSI_DEBUG"])
    }

    @get:Rule val folder = TemporaryFolder()

    private fun installed(id: String, kind: String): Paths {
        val paths = Paths(folder.root, File("/lib"))
        val json = JSONObject().put("name", id).put("catalog", entry(id, kind).json)
        DriverImport.folder(paths, id).apply { mkdirs() }.resolve("driver.json").writeText(json.toString())
        return paths
    }

    @Test fun `an OpenGL driver's own Mesa comes before the runtime's`() {
        val paths = installed("freedreno", "opengl")
        assertEquals(listOf(DriverImport.folder(paths, "freedreno"), paths.lib),
                     Drivers.libraryPath(paths, "freedreno"))
    }

    @Test fun `a Vulkan driver's folder only adds to the runtime`() {
        val paths = installed("turnip", "vulkan")
        assertEquals(listOf(paths.lib, DriverImport.folder(paths, "turnip")), Drivers.libraryPath(paths, "turnip"))
        assertEquals(listOf(paths.lib, DriverImport.folder(paths, "imported")), Drivers.libraryPath(paths, "imported"))
        assertEquals(listOf(paths.lib), Drivers.libraryPath(paths, "cpu"))
    }

    @Test fun `only the CPU and OpenGL drivers draw without Zink`() {
        val paths = installed("freedreno", "opengl")
        assertFalse(Drivers.usesZink(paths, "freedreno"))
        assertFalse(Drivers.usesZink(paths, "cpu"))
        assertTrue(Drivers.usesZink(paths, "phone"))
        assertTrue(Drivers.usesZink(paths, "turnip"))
    }
}
