package app.pocketfoundry.core.drivers

import android.content.Context
import android.os.Build
import android.util.Log
import app.pocketfoundry.core.system.Http
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.TAG
import app.pocketfoundry.core.system.objects
import app.pocketfoundry.core.system.strings
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

/**
 * The drivers the app downloads: pocket-foundry-drivers' catalog, which that repository's CI builds from source and
 * signs. Only a catalog signed with [KEY] is read, and each download must match the SHA-256 in it.
 */
object Catalog {
    enum class Kind { VULKAN, OPENGL }

    class Setting(val id: String, val label: String, val about: String, val env: Map<String, String>)

    class Test(val phone: String, val gpu: String, val android: Int?, val result: String, val fps: String?)

    /** A driver as the catalog lists it, or as it was when installed (DriverDownload keeps that copy). */
    class Entry(val json: JSONObject) {
        val id: String = json.getString("id")
        val name: String = json.getString("name")
        val kind = Kind.valueOf(json.getString("kind").uppercase())
        val about = json.optString("about")
        val madeBy = json.optString("madeBy")
        val builtFor = json.optString("builtFor")
        val priority = json.optInt("priority", 100)
        val forGpus = json.optString("for")
        val gpus = json.optJSONArray("gpus")?.strings().orEmpty()
        val family = json.optJSONObject("family")?.optString("name").orEmpty()
        val familyGpus = json.optJSONObject("family")?.optJSONArray("gpus")?.strings().orEmpty()
        val androidMin = json.optJSONObject("android")?.optInt("min") ?: 0
        val env = json.optJSONObject("env").toMap()
        val settings = json.optJSONArray("settings")?.objects().orEmpty().map {
            Setting(it.getString("id"), it.getString("label"), it.optString("about"), it.optJSONObject("env").toMap())
        }
        /** Factorio's GPU texture compressor fails on some drivers (PanVK): the CPU compresses instead. */
        val cpuCompression = json.optString("compression") == "cpu"
        val tested = json.optJSONArray("tested")?.objects().orEmpty().map {
            Test(it.getString("phone"), it.optString("gpu"), it.optInt("android").takeIf { a -> a > 0 },
                 it.optString("result"), it.optString("fps").ifEmpty { null })
        }
        val version = json.optString("version")
        /** The Mesa it's built on; [version] is a fork's own where it has one. */
        val mesa = json.optString("mesa")
        val source = json.optString("source")
        /** yyyy-mm-dd, when CI last built it. */
        val updated = json.optString("updated")
        val url: String = json.getJSONObject("file").getString("url")
        val sha256: String = json.getJSONObject("file").getString("sha256")
        val size = json.getJSONObject("file").optLong("size")
    }

    class Fix(val id: String, val name: String, val drivers: List<String>, val gpus: List<String>, val androidMin: Int,
              val env: Map<String, String>)

    class Contents(val drivers: List<Entry>, val fixes: List<Fix>) {
        fun find(id: String) = drivers.firstOrNull { it.id == id }
    }

    private const val RELEASE = "https://github.com/JulioFerrero/pocket-foundry-drivers/releases/download/catalog"
    /** The public half of the key CI signs the catalog with (ECDSA P-256, X.509 DER in Base64). */
    internal const val KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE89VvMl3NpuWlOXjxOXHsT4xAOi7kYZOaKzrOQBFYpR8NTCA/" +
        "UiXqSJLcqjH7bFcwUEU3oXRK2wE310agm1oubg=="

    @Volatile private var contents: Contents? = null

    /** How this launch's [refresh] went, for the Logs screen: null before it tried. */
    @Volatile var status: String? = null
        private set

    /** Whether this launch got the newest catalog: a game's start tries again if not. */
    val fetched get() = status?.startsWith(FETCHED) == true

    /** The one [refresh] last downloaded, for a start without a connection; empty before the first. */
    fun get(context: Context): Contents = contents ?: read(Paths(context)).also { contents = it }

    private fun read(paths: Paths): Contents = try {
        if (paths.catalog.exists()) parse(paths.catalog.readText()) else Contents(emptyList(), emptyList())
    } catch (e: Exception) {
        Log.w(TAG, "The saved driver catalog is unreadable", e)
        Contents(emptyList(), emptyList())
    }

    /** Off the main thread. Throws when it can't be downloaded, or its signature isn't [KEY]'s. */
    fun refresh(context: Context): Contents {
        val paths = Paths(context)
        val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        try {
            // Not long: the start waits for it, and a game's start tries again. 5 s was too short on a Galaxy S20
            // right after the app was installed.
            val data = Http.get("$RELEASE/catalog.json", timeoutMs = 10_000)
            val signature = Http.get("$RELEASE/catalog.json.sig", timeoutMs = 10_000)
            if (!verify(data, signature, publicKey(KEY))) throw IOException("its signature doesn't match")
            val parsed = parse(data.decodeToString())
            val staged = File(paths.files, "catalog.json.new")
            staged.writeBytes(data)
            if (!staged.renameTo(paths.catalog)) throw IOException("it couldn't be kept")
            contents = parsed
            status = "$FETCHED at $time, ${parsed.drivers.size} drivers"
            return parsed
        } catch (e: Exception) {
            status = "not fetched at $time: ${e.javaClass.simpleName}: ${e.message}"
            throw e
        }
    }

    private const val FETCHED = "fetched"

    internal fun publicKey(base64: String): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(base64)))

    /** [signature]: DER, as `openssl dgst -sha256 -sign` writes it. */
    internal fun verify(data: ByteArray, signature: ByteArray, key: PublicKey): Boolean = try {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(data)
            verify(signature)
        }
    } catch (e: java.security.SignatureException) {
        false   // not a signature at all
    }

    /** A driver or fix the app doesn't understand is left out, so a newer catalog can't break an older app. */
    internal fun parse(text: String): Contents {
        val json = JSONObject(text)
        val drivers = json.optJSONArray("drivers")?.objects().orEmpty()
            .mapNotNull { runCatching { Entry(it) }.getOrNull() }
        val fixes = json.optJSONArray("fixes")?.objects().orEmpty().mapNotNull {
            runCatching {
                Fix(it.getString("id"), it.optString("name"), it.getJSONArray("drivers").strings(),
                    it.getJSONArray("gpus").strings(), it.optJSONObject("android")?.optInt("min") ?: 0,
                    it.getJSONObject("env").toMap())
            }.getOrNull()
        }
        return Contents(drivers, fixes)
    }

    /** Built for [gpu]: the drivers listed For your GPU. */
    fun fits(entry: Entry, gpu: String?, android: Int) =
        gpu != null && android >= entry.androidMin && entry.gpus.any { matches(it, gpu) }

    /** Not built for [gpu], but for its family: the ones To try. */
    fun mightFit(entry: Entry, gpu: String?, android: Int) =
        gpu != null && android >= entry.androidMin && !fits(entry, gpu, android) &&
            entry.familyGpus.any { matches(it, gpu) }

    /** What [contents]' fixes set for [driver] on this GPU and Android. */
    fun fixes(contents: Contents, driver: String, gpu: String?, android: Int): Map<String, String> =
        contents.fixes.filter { fix ->
            driver in fix.drivers && gpu != null && android >= fix.androidMin && fix.gpus.any { matches(it, gpu) }
        }.fold(emptyMap()) { env, fix -> env + fix.env }

    /** [pattern]'s * stands for any text and ? for one character: "Adreno (TM) 7??", "Mali-G76*". */
    internal fun matches(pattern: String, name: String): Boolean =
        Regex(pattern.split('*').joinToString(".*") { part -> part.split('?').joinToString(".") { Regex.escape(it) } })
            .matches(name)

    /** "16" on Android 16. */
    val android: Int get() = Build.VERSION.RELEASE.substringBefore('.').toIntOrNull() ?: (Build.VERSION.SDK_INT - 20)

    private fun JSONObject?.toMap(): Map<String, String> =
        this?.keys()?.asSequence()?.associateWith { getString(it) }.orEmpty()
}
