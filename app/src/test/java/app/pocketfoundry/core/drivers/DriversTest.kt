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

    private val gles = listOf("mobileglues")

    @Test fun `the catalog's drivers for the GPU first, then the phone's own, then MobileGlues, then the CPU`() {
        assertEquals("turnip", Drivers.recommend(listOf("turnip", "freedreno"), vulkan = true, gles) { false })
        assertEquals("phone", Drivers.recommend(emptyList(), vulkan = true, gles) { false })
        assertEquals("mobileglues", Drivers.recommend(emptyList(), vulkan = false, gles) { false })
        assertEquals("cpu", Drivers.recommend(emptyList(), vulkan = false, emptyList()) { false })
    }

    @Test fun `a driver that didn't start is passed over`() {
        assertEquals("freedreno",
                     Drivers.recommend(listOf("turnip", "freedreno"), vulkan = true, gles) { it == "turnip" })
        assertEquals("phone", Drivers.recommend(listOf("turnip"), vulkan = true, gles) { it == "turnip" })
        assertEquals("mobileglues", Drivers.recommend(emptyList(), vulkan = true, gles) { it == "phone" })
        assertEquals("cpu",
                     Drivers.recommend(emptyList(), vulkan = true, gles) { it == "phone" || it == "mobileglues" })
        assertEquals("cpu", Drivers.recommend(listOf("turnip"), vulkan = true, gles) { true })
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

    @Test fun `the catalog's CPU driver is its own Mesa too, picked by the catalog's settings`() {
        val cpu = entry("cpu", "cpu", """{"MESA_LOADER_DRIVER_OVERRIDE": "llvmpipe", "LIBGL_ALWAYS_SOFTWARE": "1"}""")
        val environment = Drivers.environment(paths, "cpu", cpu, emptyMap())
        assertEquals("llvmpipe", environment["MESA_LOADER_DRIVER_OVERRIDE"])
        assertEquals("1", environment["LIBGL_ALWAYS_SOFTWARE"])
        assertEquals(null, environment["VK_DRIVER_FILES"])
    }

    @Test fun `MobileGlues draws on the phone's OpenGL ES through libhybris, without Zink or Vulkan`() {
        val gles = entry("mobileglues", "gles", """{"MG_X": "1"}""")
        val environment = Drivers.environment(paths, "mobileglues", gles, mapOf("FIX" to "y")) { "33" }
        val folder = DriverImport.folder(paths, "mobileglues")
        assertEquals("1", environment["SDL_VIDEO_FORCE_EGL"])
        assertEquals(File(folder, "libmgx11.so").path, environment["SDL_EGL_LIBRARY"])
        assertEquals(File(folder, "libmobileglues.so").path, environment["SDL_OPENGL_LIBRARY"])
        assertEquals("/files/usr/lib/hybris/libEGL.so.1", environment["MG_EGL_LIBRARY"])
        assertEquals("/files/usr/lib/hybris/libGLESv2.so.2", environment["MG_GLES_LIBRARY"])
        assertEquals("1", environment["MG_X"])
        assertEquals("y", environment["FIX"])
        assertEquals("33", environment["HYBRIS_VNDK_VERSION"])
        assertEquals(null, environment["VK_DRIVER_FILES"])
    }

    @Test fun `the CPU is for any GPU, like the phone's driver`() {
        val cpu = Drivers.Driver("cpu", "CPU", Drivers.Origin.CATALOG, "", entry = entry("cpu", "cpu"))
        assertTrue(Drivers.forAnyGpu(cpu))
        assertTrue(Drivers.fits(cpu))
        assertFalse(Drivers.forAnyGpu(Drivers.Driver("turnip", "Turnip", Drivers.Origin.CATALOG, "",
                                                     entry = entry("turnip", "vulkan"))))
        assertTrue(Drivers.forAnyGpu(Drivers.Driver("mobileglues", "MobileGlues", Drivers.Origin.CATALOG, "",
                                                    entry = entry("mobileglues", "gles"))))
    }

    @Test fun `the phone's driver keeps textures through its compat layer only`() {
        val ways = Drivers.textureWays(paths, Drivers.PHONE, mapOf("A" to "1"))
        assertEquals(listOf(Drivers.TextureWay.LAYER), ways.map { it.saved })
        assertEquals(mapOf("HYBRIS_BC_TEXTURES" to "1"), ways.single().environment)
    }

    @Test fun `a Vulkan driver is tried as it is, then under the compat layer with its own stand-in off`() {
        val environment = mapOf("PANVK_DEBUG" to "sync", "MESA_VK_WSI_DEBUG" to "sw")
        val ways = Drivers.textureWays(paths, "panvk-kbase-26", environment)
        assertEquals(listOf(Drivers.TextureWay.DRIVER, Drivers.TextureWay.LAYER, Drivers.TextureWay.LAYER_VULKAN_13),
                     ways.map { it.saved })
        assertEquals("1.3", ways.last().environment["MESA_VK_VERSION_OVERRIDE"])
        assertEquals(ways[1].environment, ways.last().environment - "MESA_VK_VERSION_OVERRIDE")
        assertEquals(emptyMap<String, String>(), ways.first().environment)
        assertEquals(mapOf("HYBRIS_BC_TEXTURES" to "1", "VK_LAYER_PATH" to "/files/usr/lib/hybris",
                           "VK_INSTANCE_LAYERS" to "VK_LAYER_HYBRIS_compat", "PANVK_DEBUG" to "sync,no_bc_emul",
                           "HYBRIS_MALI_SPEC_CONSTANTS" to "0"),
                     Drivers.textureWay(paths, "panvk-kbase-26", environment, Drivers.TextureWay.LAYER))
    }

    @Test fun `the test picture draws with the drivers' stand-ins for BC off`() {
        assertEquals(mapOf("HYBRIS_BC_TEXTURES" to "0"), Drivers.withoutStandIns(paths, Drivers.PHONE, emptyMap()))
        assertEquals(mapOf("PANVK_DEBUG" to "sync,no_bc_emul"),
                     Drivers.withoutStandIns(paths, "panvk-kbase-26", mapOf("PANVK_DEBUG" to "sync")))
        assertEquals(emptyMap<String, String>(),
                     Drivers.withoutStandIns(installed("freedreno", "opengl"), "freedreno", emptyMap()))
    }

    @Test fun `a driver with its own OpenGL keeps textures its own way only`() {
        val paths = installed("freedreno", "opengl")
        assertEquals(listOf(Drivers.TextureWay.DRIVER),
                     Drivers.textureWays(paths, "freedreno", emptyMap()).map { it.saved })
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

    @Test fun `an OpenGL or CPU driver's own Mesa comes before the runtime's`() {
        val paths = installed("freedreno", "opengl")
        installed("cpu", "cpu")
        assertEquals(listOf(DriverImport.folder(paths, "freedreno"), paths.lib),
                     Drivers.libraryPath(paths, "freedreno"))
        assertEquals(listOf(DriverImport.folder(paths, "cpu"), paths.lib), Drivers.libraryPath(paths, "cpu"))
    }

    @Test fun `a Vulkan driver's folder only adds to the runtime`() {
        val paths = installed("turnip", "vulkan")
        assertEquals(listOf(paths.lib, DriverImport.folder(paths, "turnip")), Drivers.libraryPath(paths, "turnip"))
        assertEquals(listOf(paths.lib, DriverImport.folder(paths, "imported")), Drivers.libraryPath(paths, "imported"))
        assertEquals(listOf(paths.lib), Drivers.libraryPath(paths, "phone"))
    }

    @Test fun `High sprites need room for what the textures really cost`() {
        assertTrue(Drivers.highSpritesFit(15247, 1.0))
        assertFalse(Drivers.highSpritesFit(2322, 1.0))
        // A Redmi Note 14 Pro 5G's PanVK had 5564 MB free: enough at 1, through the converter too.
        assertTrue(Drivers.highSpritesFit(5564, 2.0))
        assertFalse(Drivers.highSpritesFit(3500, 2.0))
        assertFalse(Drivers.highSpritesFit(null, 1.0))
    }

    @Test fun `every driver through Mesa is checked, not MobileGlues nor the CPU`() {
        val paths = installed("freedreno", "opengl")
        installed("cpu", "cpu")
        installed("mobileglues", "gles")
        assertTrue(Drivers.checked(paths, "freedreno"))
        assertFalse(Drivers.checked(paths, "cpu"))
        assertFalse(Drivers.checked(paths, "mobileglues"))
        assertFalse(Drivers.checked(paths, "listed", entry("listed", "gles")))
        assertFalse(Drivers.checked(paths, "listed", entry("listed", "cpu")))
        assertTrue(Drivers.checked(paths, "phone"))
        assertTrue(Drivers.checked(paths, "turnip"))
    }

    private val today = java.time.LocalDate.of(2026, 11, 10)

    @Test fun `a driver with its own version shows it and its build day`() {
        assertEquals("1.2.9 · 10 Nov", Drivers.buildVersion("1.2.9", "26.3.0-devel", "2026-11-10", today))
        assertEquals("0.1.0 RC3 · 10 Nov", Drivers.buildVersion("0.1.0 RC3", "26.3.0-devel", "2026-11-10", today))
    }

    @Test fun `one built from Mesa's development code shows Mesa's major version and its build day`() {
        assertEquals("Mesa 27 · 10 Nov", Drivers.buildVersion("27.0.0-devel", "27.0.0-devel", "2026-11-10", today))
        assertEquals("Mesa 27 · Oct 2025", Drivers.buildVersion("27.0.0-devel", "27.0.0-devel", "2025-10-01", today))
    }

    @Test fun `a build without a day shows its version alone`() {
        assertEquals("1.2.9", Drivers.buildVersion("1.2.9", "", "", today))
    }

    @Test fun `the phone's driver shows its maker's version`() {
        assertEquals("Qualcomm 0800.73",
                     Drivers.makersVersion("OpenGL ES 3.2 V@0800.73.1 (GIT@22ea836242, If5f3cc8599, 1780462147)"))
        assertEquals("Arm r38p1", Drivers.makersVersion("OpenGL ES 3.2 v1.r38p1-01eac0.2bad4a3b3b8c45d7"))
        assertEquals("build 1.15@6133109", Drivers.makersVersion("OpenGL ES 3.2 build 1.15@6133109"))
        assertEquals("(4.1 Metal - 91.7)", Drivers.makersVersion("OpenGL ES 3.0 (4.1 Metal - 91.7)"))
    }
}
