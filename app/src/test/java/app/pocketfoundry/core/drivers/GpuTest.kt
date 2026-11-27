package app.pocketfoundry.core.drivers

import org.junit.Assert.assertEquals
import org.junit.Test

class GpuTest {
    @Test fun `ANGLE's name for the GPU is the GPU's own, as Samsung and ANGLE itself wrap it`() {
        assertEquals("Samsung Xclipse 940", Gpu.shownName("ANGLE ((Samsung Xclipse 940) on Vulkan 1.3.279)"))
        assertEquals("PowerVR C-Series CXTP-48-1536 MC1", Gpu.shownName(
            "ANGLE (Imagination Technologies, Vulkan 1.4.317 (PowerVR C-Series CXTP-48-1536 MC1 (0x70061042)), " +
                "PowerVR C-Series Vulkan Driver-1.662.3024)"))
        assertEquals("Adreno (TM) 740", Gpu.shownName(
            "ANGLE (Qualcomm, Vulkan 1.3.128 (Adreno (TM) 740 (0x43050a01)), Qualcomm Technologies Inc. Adreno Vulkan Driver)"))
    }

    @Test fun `a GPU's name without ANGLE stays as it is`() {
        assertEquals("Mali-G610 MC6", Gpu.shownName("Mali-G610 MC6"))
        assertEquals("Adreno (TM) 830", Gpu.shownName("Adreno (TM) 830"))
        assertEquals("ANGLE (something new)", Gpu.shownName("ANGLE (something new)"))
    }
}
