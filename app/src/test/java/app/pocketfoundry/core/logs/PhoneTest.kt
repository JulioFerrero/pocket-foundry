package app.pocketfoundry.core.logs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneTest {
    @Test fun `a model that is only a code gets the name its maker's property gives`() {
        assertEquals("Xiaomi 2209116AG (Redmi Note 12 Pro)",
                     Phone.label("Xiaomi", "2209116AG", mapOf("ro.product.marketname" to "Redmi Note 12 Pro")))
        assertEquals("realme RMX5110 (realme P4 5G)",
                     Phone.label("realme", "RMX5110", mapOf("ro.vendor.oplus.market.name" to "realme P4 5G")))
        assertEquals("vivo V2520A (iQOO Neo11)",
                     Phone.label("vivo", "V2520A", mapOf("ro.vivo.market.name" to "iQOO Neo11")))
    }

    @Test fun `a phone that doesn't say, or whose model is the name, keeps its model alone`() {
        assertEquals("Google Pixel 9 Pro", Phone.label("Google", "Pixel 9 Pro", emptyMap()))
        assertNull(Phone.name("Pixel 9 Pro", mapOf("ro.product.marketname" to "pixel 9 pro")))
        assertNull(Phone.name("SM-S721B", mapOf("ro.product.marketname" to "  ")))
    }

    @Test fun `an empty property gives way to the next one`() {
        assertEquals("HUAWEI nova 5T", Phone.name("YAL-L21", mapOf("ro.product.marketname" to "",
                                                                    "ro.config.marketing_name" to "HUAWEI nova 5T")))
    }
}
