package app.pocketfoundry.drivers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test fun `a GPU that couldn't be named gets the benefit of the doubt`() {
        assertTrue(Gpu.isSupported(null))
    }

    @Test fun `Adreno 600 and newer are supported`() {
        for (name in listOf("Adreno (TM) 600", "Adreno (TM) 618", "Adreno (TM) 740", "Adreno (TM) 830"))
            assertTrue(name, Gpu.isSupported(name))
    }

    @Test fun `older Adrenos aren't`() {
        for (name in listOf("Adreno (TM) 530", "Adreno (TM) 506", "Adreno (TM) 599"))
            assertFalse(name, Gpu.isSupported(name))
    }

    @Test fun `an Adreno named some other way gets the benefit of the doubt`() {
        assertTrue(Gpu.isSupported("Adreno X1-85"))
        assertTrue(Gpu.isSupported("Adreno 740"))
    }

    @Test fun `other GPUs aren't`() {
        for (name in listOf("Mali-G610", "PowerVR B-Series BXM-8-256", "llvmpipe (LLVM 19.1.0, 128 bits)", ""))
            assertFalse(name, Gpu.isSupported(name))
    }
}
