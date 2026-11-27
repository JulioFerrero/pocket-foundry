package app.pocketfoundry.core.logs

import android.os.Build
import app.pocketfoundry.core.runtime.Runtime

/** The phone as reports and the Logs name it: its maker, its model, and the name it's sold under. */
object Phone {
    /** "Xiaomi 2209116AG (Redmi Note 12 Pro)", or "Google Pixel 9 Pro" where the model is the name already. */
    val label: String by lazy { label(Build.MANUFACTURER, Build.MODEL, Runtime.properties) }

    /** The name it's sold under, where the model is only a code ("2209116AG") and the maker's property says it. */
    val name: String? by lazy { name(Build.MODEL, Runtime.properties) }

    /**
     * Where makers put it: Xiaomi, Redmi and POCO; OPPO, realme and OnePlus, then their older one; vivo and iQOO; Huawei
     * and Honor. A property the app may not read is missing from [Runtime.properties], and the next one is tried.
     */
    private val PROPERTIES = listOf("ro.product.marketname", "ro.product.vendor.marketname",
                                    "ro.vendor.oplus.market.name", "ro.oppo.market.name", "ro.vivo.market.name",
                                    "ro.config.marketing_name")

    internal fun name(model: String, properties: Map<String, String>): String? =
        PROPERTIES.firstNotNullOfOrNull { properties[it]?.trim()?.ifEmpty { null } }
            ?.takeUnless { it.equals(model, ignoreCase = true) }

    internal fun label(maker: String, model: String, properties: Map<String, String>): String =
        "$maker $model" + name(model, properties)?.let { " ($it)" }.orEmpty()
}
