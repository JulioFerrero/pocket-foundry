package app.pocketfoundry.core.game

import app.pocketfoundry.core.drivers.Drivers.Compression
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FactorioConfigTest {
    private fun update(ini: String, keyboard: Boolean = false, compression: Compression = Compression.GPU,
                       textures: String? = null, effects: Boolean? = null, atlasCache: Boolean = true,
                       maxTextureSize: Int? = null) =
        FactorioConfig.update(ini, "/game/data", "/game/write", keyboard, "normal", compression, textures, effects,
                              atlasCache, maxTextureSize)

    /** [key]'s value in [section], or null. */
    private fun value(ini: String, section: String, key: String) = ini.lines()
        .dropWhile { it != "[$section]" }.drop(1).takeWhile { !it.startsWith("[") }
        .firstOrNull { it.startsWith("$key=") }?.substringAfter('=')

    @Test fun `a driver Factorio can't size the atlases for gets them at most 8192 wide, the others as they were`() {
        assertEquals("8192", value(update("", maxTextureSize = 8192), "graphics", "max-texture-size"))
        assertNull(value(update(""), "graphics", "max-texture-size"))
    }

    @Test fun `setIni adds a missing section at the end`() {
        assertEquals("; version=13\n[general]\nlocale=\n\n[graphics]\na=1\n",
                     FactorioConfig.setIni("; version=13\n[general]\nlocale=\n", "graphics", "a", "1"))
    }

    @Test fun `setIni adds a missing key right under its section's header`() {
        assertEquals("[graphics]\na=1\nb=2\n\n[sound]\nc=3\n",
                     FactorioConfig.setIni("[graphics]\nb=2\n\n[sound]\nc=3\n", "graphics", "a", "1"))
    }

    @Test fun `setIni replaces the key's value in its section`() {
        assertEquals("[graphics]\na=1\nb=2\n", FactorioConfig.setIni("[graphics]\na=0\nb=2\n", "graphics", "a", "1"))
    }

    @Test fun `setIni leaves the same key in a later section alone`() {
        assertEquals("[graphics]\na=1\nb=2\n\n[sound]\na=0\n",
                     FactorioConfig.setIni("[graphics]\nb=2\n\n[sound]\na=0\n", "graphics", "a", "1"))
    }

    @Test fun `setIni leaves the same key in an earlier section alone`() {
        assertEquals("[sound]\na=0\n\n[graphics]\na=1\nb=2\n",
                     FactorioConfig.setIni("[sound]\na=0\n\n[graphics]\nb=2\n", "graphics", "a", "1"))
    }

    @Test fun `setIni doesn't count a commented-out default as the key`() {
        assertEquals("[graphics]\na=1\n; a=0\n", FactorioConfig.setIni("[graphics]\n; a=0\n", "graphics", "a", "1"))
    }

    @Test fun `setIni replaces every copy of the key in the section`() {
        assertEquals("[graphics]\na=1\na=1\n", FactorioConfig.setIni("[graphics]\na=0\na=5\n", "graphics", "a", "1"))
    }

    @Test fun `setIni takes regex characters in keys and values literally`() {
        assertEquals("[s]\na.b=\$1\\\naxb=0\n", FactorioConfig.setIni("[s]\naxb=0\n", "s", "a.b", "\$1\\"))
        assertEquals("[s]\na.b=\$1\\\n", FactorioConfig.setIni("[s]\na.b=0\n", "s", "a.b", "\$1\\"))
    }

    @Test fun `a new config gets the version header and all our settings`() {
        val config = update("")
        assertTrue(config.startsWith("; version=13\n"))
        assertEquals("/game/write", value(config, "path", "write-data"))
        assertEquals("/game/data", value(config, "path", "read-data"))
        assertEquals("game-controller", value(config, "input", "input-method"))
        assertEquals("1.25", value(config, "interface", "custom-proportional-ui-scale"))
        assertEquals("manual-display-points", value(config, "interface", "ui-scale-mode"))
        assertEquals("true", value(config, "graphics", "gpu-accelerated-compression"))
        assertEquals("true", value(config, "graphics", "gpu-accelerated-mipmap-compression"))
        assertEquals("normal", value(config, "graphics", "graphics-quality"))
    }

    @Test fun `the player's other settings and the version header stay`() {
        val config = update("; version=11\n[general]\nlocale=es\n\n" +
                            "[graphics]\ngraphics-quality=high\nmax-texture-size=4096\n")
        assertTrue(config.startsWith("; version=11\n"))
        assertEquals("es", value(config, "general", "locale"))
        assertEquals("normal", value(config, "graphics", "graphics-quality"))
        assertEquals("4096", value(config, "graphics", "max-texture-size"))
    }

    @Test fun `a file without a version header starts over`() {
        val config = update("[general]\nlocale=es\n")
        assertTrue(config.startsWith("; version=13\n"))
        assertFalse(config.contains("locale"))
    }

    @Test fun `updating twice gives the same file`() {
        val once = update("; version=13\n[general]\nlocale=es\n", effects = false)
        assertEquals(once, update(once, effects = false))
    }

    @Test fun `fast start keeps the atlases on storage, compressed`() {
        val config = update("")
        assertEquals("true", value(config, "graphics", "cache-sprite-atlas"))
        assertEquals("true", value(config, "graphics", "compress-sprite-atlas-cache"))
        assertEquals("false", value(update(config, atlasCache = false), "graphics", "cache-sprite-atlas"))
    }

    @Test fun `keyboard picks keyboard and mouse`() {
        assertEquals("keyboard-and-mouse", value(update("", keyboard = true), "input", "input-method"))
    }

    @Test fun `a driver without GPU compression turns it off`() {
        val config = update("", compression = Compression.CPU)
        assertEquals("false", value(config, "graphics", "gpu-accelerated-compression"))
        assertEquals("false", value(config, "graphics", "gpu-accelerated-mipmap-compression"))
    }

    private val playerCompression = "; version=13\n[graphics]\ntexture-compression-level=high-quality\n"

    @Test fun `a textures choice sets the texture compression`() {
        val config = update(playerCompression, compression = Compression.NONE, textures = "none")
        assertEquals("none", value(config, "graphics", "texture-compression-level"))
        assertEquals("false", value(config, "graphics", "gpu-accelerated-compression"))
    }

    @Test fun `no textures choice keeps the player's texture compression`() {
        val config = update(playerCompression)
        assertEquals("high-quality", value(config, "graphics", "texture-compression-level"))
    }

    @Test fun `a driver that needs them gets uncompressed textures`() {
        assertEquals("none", FactorioConfig.textures(uncompressed = true, uncompressedApplied = false))
        assertEquals("none", FactorioConfig.textures(uncompressed = true, uncompressedApplied = true))
    }

    @Test fun `the next driver puts the default compression back once`() {
        assertEquals("low-quality", FactorioConfig.textures(uncompressed = false, uncompressedApplied = true))
    }

    @Test fun `otherwise the game's menu decides the texture compression`() {
        assertNull(FactorioConfig.textures(uncompressed = false, uncompressedApplied = false))
    }

    @Test fun `a UI scale mode the player chose stays`() {
        val config = update("; version=13\n[interface]\nui-scale-mode=auto\n")
        assertEquals("auto", value(config, "interface", "ui-scale-mode"))
        assertNull(value(config, "interface", "custom-proportional-ui-scale"))
    }

    @Test fun `no effects choice leaves the effect settings alone`() {
        val config = update("; version=13\n[graphics]\nshow-smoke=false\n")
        assertEquals("false", value(config, "graphics", "show-smoke"))
        assertNull(value(config, "graphics", "show-clouds"))
    }

    @Test fun `an effects choice sets every effect setting`() {
        // Some of Factorio's own names for them, as its graphics settings save them.
        val effects = listOf("show-smoke", "show-clouds", "show-decoratives", "high-quality-terrain")
        val off = update("; version=13\n[graphics]\nshow-smoke=true\n", effects = false)
        for (key in effects) assertEquals(key, "false", value(off, "graphics", key))
        val on = update(off, effects = true)
        for (key in effects) assertEquals(key, "true", value(on, "graphics", key))
        assertFalse(on, on.lines().any { it.endsWith("=false") })
    }

    @Test fun `Light forces the effects off`() {
        assertEquals(false, FactorioConfig.effects(light = true, lightApplied = false))
        assertEquals(false, FactorioConfig.effects(light = true, lightApplied = true))
    }

    @Test fun `turning Light off turns the effects on once`() {
        assertEquals(true, FactorioConfig.effects(light = false, lightApplied = true))
    }

    @Test fun `without Light the game's menu decides`() {
        assertNull(FactorioConfig.effects(light = false, lightApplied = false))
    }
}
