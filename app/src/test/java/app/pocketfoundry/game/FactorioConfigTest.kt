package app.pocketfoundry.game

import app.pocketfoundry.drivers.Drivers.Compression
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FactorioConfigTest {
    private val effectKeys = listOf("show-smoke", "show-clouds", "show-fog", "show-space-dust", "show-decoratives",
                                    "show-particles", "show-item-shadows", "show-inserter-shadows",
                                    "additional-terrain-effects", "high-quality-terrain", "elevated-rail-shadows")

    private fun update(ini: String, keyboard: Boolean = false, compression: Compression = Compression.GPU,
                       textures: String? = null, effects: Boolean? = null) =
        FactorioConfig.update(ini, "/game/data", "/game/write", keyboard, "normal", compression, textures, effects)

    /** [key]'s value in [section], or null. */
    private fun value(ini: String, section: String, key: String) = ini.lines()
        .dropWhile { it != "[$section]" }.drop(1).takeWhile { !it.startsWith("[") }
        .firstOrNull { it.startsWith("$key=") }?.substringAfter('=')

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
        assertEquals("; version=13\n\n" +
                     "[path]\nwrite-data=/game/write\nread-data=/game/data\n\n" +
                     "[input]\ninput-method=game-controller\n\n" +
                     "[interface]\ncustom-proportional-ui-scale=1.25\nui-scale-mode=manual-display-points\n\n" +
                     "[graphics]\ngpu-accelerated-mipmap-compression=true\ngpu-accelerated-compression=true\n" +
                     "graphics-quality=normal\n",
                     update(""))
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
        val off = update("; version=13\n[graphics]\nshow-smoke=true\n", effects = false)
        for (key in effectKeys) assertEquals(key, "false", value(off, "graphics", key))
        val on = update(off, effects = true)
        for (key in effectKeys) assertEquals(key, "true", value(on, "graphics", key))
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
