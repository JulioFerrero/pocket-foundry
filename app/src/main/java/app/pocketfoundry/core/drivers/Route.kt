package app.pocketfoundry.core.drivers

import app.pocketfoundry.core.system.Paths
import java.io.File

/**
 * How a driver takes the game's OpenGL to the GPU. Everything that depends on the kind of driver follows from it: what
 * the game's environment gets, where its libraries are found, whether DriverCheck checks it and how the game's
 * textures can be kept.
 */
enum class Route(
    /** What draws through it, said before its build: "Zink · Arm r38p1". Null where the build says enough. */
    val engine: String?,
    /** It plays on any GPU, so it's listed whatever this phone's is. */
    val anyGpu: Boolean,
    /** DriverCheck checks it: the game reaches it through Mesa, as the check's program does. */
    val checked: Boolean,
    /** A whole Mesa of its own, found before the runtime's. */
    val ownMesa: Boolean,
) {
    /** Zink on Android's own Vulkan driver, which libhybris loads (runtime/hybris). */
    PHONE("Zink", anyGpu = true, checked = true, ownMesa = false),
    /** Zink on a catalog Vulkan driver, a Mesa with pocket-foundry-drivers' patches: Turnip, PanVK, RADV. */
    MESA_VULKAN(null, anyGpu = false, checked = true, ownMesa = false),
    /** Zink on a Vulkan driver the player imported, without those patches. */
    IMPORTED(null, anyGpu = false, checked = true, ownMesa = false),
    /** A catalog Mesa with its own OpenGL driver: Freedreno. */
    MESA_OPENGL(null, anyGpu = false, checked = true, ownMesa = true),
    /** Mesa's llvmpipe, which a game falls back on anyway. */
    CPU("llvmpipe", anyGpu = true, checked = false, ownMesa = true),
    /** MobileGlues on the phone's own OpenGL ES: no Mesa, so nothing for DriverCheck's program to try. */
    MOBILEGLUES("MobileGlues", anyGpu = true, checked = false, ownMesa = false);

    /**
     * What the game's environment gets for driver [id] on this route, under the driver's own settings. [vndk]:
     * Android's VNDK version, for libhybris's linker.
     */
    fun environment(paths: Paths, id: String, vndk: () -> String): Map<String, String> = when (this) {
        // The compat layer adds what Zink needs and the driver may lack, like null descriptors on Mali.
        PHONE -> mapOf("VK_DRIVER_FILES" to paths.hybrisIcd.path, "VK_LAYER_PATH" to paths.hybris.path,
                       "VK_INSTANCE_LAYERS" to COMPAT_LAYER) + hybris(paths, vndk())
        // Its swapchain draws into Android hardware buffers, which Termux:X11 shows as they are, when it can load
        // libhybris (pocket-foundry-drivers' mesa-x11-android-hardware-buffers.patch, in Turnip): otherwise the screen
        // copies or uploads every frame. A driver without the patch doesn't read it; POCKETFOUNDRY_HYBRIS= in its
        // Options turns it off.
        MESA_VULKAN -> hybris(paths, vndk()) + mapOf(
            "VK_DRIVER_FILES" to DriverImport.icd(paths, id).path,
            ANDROID_BUFFERS to File(paths.hybris, "libhybris-common.so.1").path)
        // Without our wait-before-present patch: the software path waits for the GPU instead.
        IMPORTED -> mapOf("VK_DRIVER_FILES" to DriverImport.icd(paths, id).path, "MESA_VK_WSI_DEBUG" to "sw")
        MESA_OPENGL, CPU -> emptyMap()
        MOBILEGLUES -> mobileGlues(DriverImport.folder(paths, id), paths) + hybris(paths, vndk())
    }

    /** Where driver [id]'s process finds its libraries: a Vulkan driver's folder only adds what the runtime lacks. */
    fun libraryPath(paths: Paths, id: String): List<File> = when {
        this == PHONE -> listOf(paths.lib)
        ownMesa -> listOf(DriverImport.folder(paths, id), paths.lib)
        else -> listOf(paths.lib, DriverImport.folder(paths, id))
    }

    /**
     * Each way the game's BC textures can be kept, over [environment], in the order DriverCheck tries them. The phone's
     * driver has the compat layer already: its converter only. Zink's other drivers first as they are, then with the
     * layer on top, their own stand-in off so the layer sees what they lack (PANVK_DEBUG; the others have none), and
     * without the layer's fixes for Arm's own shader compiler, which only the phone's driver has. The rest have no
     * Vulkan for the layer to go on.
     */
    fun textureWays(paths: Paths, environment: Map<String, String>): List<TextureWay> {
        val layer = TextureWay(TextureWay.LAYER, mapOf(CONVERTER to "1") + if (this == PHONE) emptyMap() else mapOf(
            "VK_LAYER_PATH" to listOfNotNull(environment["VK_LAYER_PATH"], paths.hybris.path).joinToString(":"),
            "VK_INSTANCE_LAYERS" to listOfNotNull(environment["VK_INSTANCE_LAYERS"], COMPAT_LAYER).joinToString(":"),
            "PANVK_DEBUG" to listOfNotNull(environment["PANVK_DEBUG"], "no_bc_emul").joinToString(","),
            "HYBRIS_MALI_SPEC_CONSTANTS" to "0"), convertsWhatsMissing = true)
        val layerAt13 = TextureWay(TextureWay.LAYER_VULKAN_13, layer.environment +
                                       ("MESA_VK_VERSION_OVERRIDE" to "1.3"), convertsWhatsMissing = true)
        return when (this) {
            PHONE -> listOf(layer)
            MESA_VULKAN, IMPORTED -> listOf(TextureWay.AS_IT_IS, layer, layerAt13)
            MESA_OPENGL, CPU, MOBILEGLUES -> listOf(TextureWay.AS_IT_IS)
        }
    }

    /**
     * For DriverCheck's test picture: the driver's stand-ins for the BC it lacks off, so Mesa unpacks the compressed
     * squares itself, exactly. The compat layer's converter on the phone's driver, PanVK's own on Zink's others (the
     * rest have none, and ignore it); the textures step judges each stand-in on its own.
     */
    fun withoutStandIns(environment: Map<String, String>): Map<String, String> = when (this) {
        PHONE -> mapOf(CONVERTER to "0")
        MESA_VULKAN, IMPORTED ->
            mapOf("PANVK_DEBUG" to listOfNotNull(environment["PANVK_DEBUG"], "no_bc_emul").joinToString(","))
        MESA_OPENGL, CPU, MOBILEGLUES -> emptyMap()
    }

    companion object {
        /** Driver [id] with [entry], its catalog entry as downloaded or as listed: null for one the player imported. */
        fun of(id: String, entry: Catalog.Entry?): Route = if (id == Drivers.PHONE) PHONE else when (entry?.kind) {
            null -> IMPORTED
            Catalog.Kind.VULKAN -> MESA_VULKAN
            Catalog.Kind.OPENGL -> MESA_OPENGL
            Catalog.Kind.CPU -> CPU
            Catalog.Kind.GLES -> MOBILEGLUES
        }

        /** Driver [id] as downloaded, or as [listed] in the catalog for one not downloaded yet. */
        fun of(paths: Paths, id: String, listed: Catalog.Entry? = null) =
            of(id, DriverImport.downloaded(paths, id) ?: listed)

        fun of(driver: Drivers.Driver) = of(driver.id, driver.entry)

        /** Read by our libhybris patches 0003 and 0004 (runtime/hybris/patches). */
        internal const val HYBRIS_PROPERTIES = "HYBRIS_PROPERTY_FILE"
        internal const val HYBRIS_VNDK = "HYBRIS_VNDK_VERSION"
        internal const val ANDROID_BUFFERS = "POCKETFOUNDRY_HYBRIS"
        /** The compat layer's converter (runtime/hybris): 1 converts what the driver lacks, 0 leaves it to Mesa. */
        internal const val CONVERTER = "HYBRIS_BC_TEXTURES"
        private const val COMPAT_LAYER = "VK_LAYER_HYBRIS_compat"

        /** Android's properties, which libhybris's linker can't read; Android 10 names its linker config by VNDK. */
        internal fun hybris(paths: Paths, vndk: String) =
            mapOf(HYBRIS_PROPERTIES to paths.androidProperties.path, HYBRIS_VNDK to vndk)

        /**
         * SDL makes the game's OpenGL context with [folder]'s EGL, a shim to MobileGlues' (libmgx11.so, from
         * pocket-foundry-drivers): MobileGlues turns OpenGL into the phone's OpenGL ES, which libhybris's EGL and GLES
         * load (runtime/hybris), and the shim gives SDL's X11 window a pbuffer the GPU copies into Android hardware
         * buffers the X server shows. No Zink, no Vulkan.
         */
        private fun mobileGlues(folder: File, paths: Paths) = mapOf(
            "SDL_VIDEO_FORCE_EGL" to "1",
            "SDL_EGL_LIBRARY" to File(folder, "libmgx11.so").path,
            "SDL_OPENGL_LIBRARY" to File(folder, "libmobileglues.so").path,
            "MG_LIBRARY" to File(folder, "libmobileglues.so").path,
            "MG_EGL_LIBRARY" to File(paths.hybris, "libEGL.so.1").path,
            "MG_GLES_LIBRARY" to File(paths.hybris, "libGLESv2.so.2").path,
            "MG_DIR_PATH" to File(folder, "mobileglues").path,
            // libhybris's EGL draws offscreen only: the shim shows the frames.
            "EGL_PLATFORM" to "null")
    }
}

/**
 * A way the game's BC textures can be kept on a driver, over its [environment]: DriverCheck tries them in order and
 * measures what each does. [saved]: its name in the check's findings. [convertsWhatsMissing]: it can't leave them
 * unpacked, where the driver doesn't say its memory.
 */
class TextureWay(val saved: String, val environment: Map<String, String>, val convertsWhatsMissing: Boolean) {
    companion object {
        /** As the driver keeps BC: its own formats, or its own stand-in for them. */
        const val DRIVER = "driver"
        /**
         * Through the compat layer's converter: BC where the driver has it, ASTC and EAC where it doesn't. Unpacked,
         * they take about five times the room: a Mali-G52 with 3.7 GB ran out of memory loading the base game.
         */
        const val LAYER = "layer"
        /**
         * The same with the driver saying it's Vulkan 1.3 (Mesa's MESA_VK_VERSION_OVERRIDE): the layer turns itself
         * off on 1.4, whose descriptor commands it doesn't handle yet, and PanVK-kbase on a Mali-G615 is 1.4. Mesa's
         * drivers only; the phone's own driver doesn't read it.
         */
        const val LAYER_VULKAN_13 = "layer at Vulkan 1.3"

        val AS_IT_IS = TextureWay(DRIVER, emptyMap(), convertsWhatsMissing = false)
    }
}
