package app.pocketfoundry.core.drivers

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class CatalogTest {
    private val catalog = Catalog.parse("""
        {"format": 1, "drivers": [
          {"id": "turnip", "name": "Zink + Turnip", "kind": "vulkan", "priority": 1,
           "gpus": ["Adreno (TM) 6??", "Adreno (TM) 7??"],
           "file": {"url": "https://example.com/turnip.tar.xz", "sha256": "ab", "size": 2}},
          {"id": "panvk", "name": "PanVK", "kind": "vulkan", "gpus": ["Mali-G76*"], "android": {"min": 10},
           "family": {"name": "Mali Bifrost", "gpus": ["Mali-G52*", "Mali-G72*", "Mali-G76*"]},
           "file": {"url": "https://example.com/panvk.tar.xz", "sha256": "cd", "size": 3}},
          {"id": "cpu", "name": "CPU", "kind": "cpu", "for": "Any GPU",
           "file": {"url": "https://example.com/cpu.tar.xz", "sha256": "12", "size": 4}},
          {"id": "future", "name": "From a newer catalog", "kind": "metal",
           "file": {"url": "https://example.com/x", "sha256": "ef"}},
          {"id": "unbuilt", "name": "No file yet", "kind": "vulkan"}
        ], "fixes": [
          {"id": "flag-hint", "name": "No black flashes on Android 16", "drivers": ["turnip"],
           "gpus": ["Adreno (TM) 740"], "android": {"min": 16},
           "env": {"FD_DEV_FEATURES": "enable_tp_ubwc_flag_hint=1"}}
        ]}""")
    private val turnip = catalog.find("turnip")!!
    private val panvk = catalog.find("panvk")!!

    @Test fun `drivers the app can't read are left out`() {
        assertEquals(listOf("turnip", "panvk", "cpu"), catalog.drivers.map { it.id })
    }

    @Test fun `a question mark is one character, a star any text`() {
        assertTrue(Catalog.matches("Adreno (TM) 7??", "Adreno (TM) 740"))
        assertFalse(Catalog.matches("Adreno (TM) 7??", "Adreno (TM) 74"))
        assertFalse(Catalog.matches("Adreno (TM) 7??", "Adreno (TM) 7400"))
        assertTrue(Catalog.matches("Mali-G76*", "Mali-G76"))
        assertTrue(Catalog.matches("Mali-G76*", "Mali-G76 MP12"))
        assertFalse(Catalog.matches("Mali-G7*", "Mali-G610"))
        assertFalse(Catalog.matches("Adreno (TM) 6??", "Adreno [TM] 640"))
    }

    @Test fun `a driver is for the GPUs it was built for`() {
        assertTrue(Catalog.fits(turnip, "Adreno (TM) 740", 16))
        assertFalse(Catalog.fits(turnip, "Adreno (TM) 530", 16))
        assertFalse(Catalog.fits(turnip, "Mali-G76", 16))
        assertFalse(Catalog.fits(turnip, null, 16))
    }

    @Test fun `a GPU of its family can try it`() {
        assertTrue(Catalog.mightFit(panvk, "Mali-G72", 12))
        assertFalse(Catalog.mightFit(panvk, "Mali-G76 MP12", 12))   // built for it: For your GPU, not To try
        assertFalse(Catalog.mightFit(panvk, "Mali-G610", 12))
    }

    @Test fun `an Android older than the driver's can't use it`() {
        assertFalse(Catalog.fits(panvk, "Mali-G76", 9))
        assertFalse(Catalog.mightFit(panvk, "Mali-G72", 9))
    }

    @Test fun `a fix applies to its drivers on its GPUs and Androids only`() {
        val hint = mapOf("FD_DEV_FEATURES" to "enable_tp_ubwc_flag_hint=1")
        assertEquals(hint, Catalog.fixes(catalog, "turnip", "Adreno (TM) 740", 16))
        assertEquals(hint, Catalog.fixes(catalog, "turnip", "Adreno (TM) 740", 17))
        assertEquals(emptyMap<String, String>(), Catalog.fixes(catalog, "turnip", "Adreno (TM) 740", 15))
        assertEquals(emptyMap<String, String>(), Catalog.fixes(catalog, "turnip", "Adreno (TM) 750", 16))
        assertEquals(emptyMap<String, String>(), Catalog.fixes(catalog, "panvk", "Adreno (TM) 740", 16))
    }

    @Test fun `the driver screen names the fixes that apply`() {
        assertEquals(listOf("No black flashes on Android 16"),
                     Catalog.fixesFor(catalog, "turnip", "Adreno (TM) 740", 16).map { it.name })
        assertEquals(emptyList<Catalog.Fix>(), Catalog.fixesFor(catalog, "turnip", "Adreno (TM) 740", 15))
    }

    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }

    private fun sign(data: ByteArray, key: java.security.PrivateKey): ByteArray =
        Signature.getInstance("SHA256withECDSA").run { initSign(key); update(data); sign() }

    @Test fun `only a catalog signed with the app's key is read`() {
        val ours = keys.generateKeyPair()
        val theirs = keys.generateKeyPair()
        val data = """{"drivers": []}""".toByteArray()
        val key = Catalog.publicKey(Base64.getEncoder().encodeToString(ours.public.encoded))
        assertTrue(Catalog.verify(data, sign(data, ours.private), key))
        assertFalse(Catalog.verify(data, sign(data, theirs.private), key))
        assertFalse(Catalog.verify("""{"drivers": [1]}""".toByteArray(), sign(data, ours.private), key))
        assertFalse(Catalog.verify(data, ByteArray(8), key))
    }

    @Test fun `the app's key is a P-256 public key`() {
        assertEquals("EC", Catalog.publicKey(Catalog.KEY).algorithm)
    }

    @Test fun `an entry keeps what the list and the download need`() {
        val entry = Catalog.Entry(JSONObject("""
            {"id": "x", "name": "X", "kind": "opengl", "version": "1.2.8", "mesa": "26.3.0-devel",
             "settings": [{"id": "a", "label": "A", "env": {"K": "v"}}], "compression": "cpu",
             "tested": [{"phone": "Galaxy S10", "gpu": "Mali-G76", "android": 12, "result": "plays", "fps": "29-37"}],
             "file": {"url": "https://example.com/x", "sha256": "ff", "size": 9}}"""))
        assertEquals(Catalog.Kind.OPENGL, entry.kind)
        assertEquals(mapOf("K" to "v"), entry.settings.single().env)
        assertEquals(12, entry.tested.single().android)
        assertEquals(100, entry.priority)
        assertFalse(entry.testing)
    }

    @Test fun `a driver in testing says so`() {
        val entry = Catalog.Entry(JSONObject("""
            {"id": "mobileglues", "name": "MobileGlues", "kind": "gles", "testing": true,
             "file": {"url": "https://example.com/x", "sha256": "ff", "size": 9}}"""))
        assertEquals(Catalog.Kind.GLES, entry.kind)
        assertTrue(entry.testing)
    }
}
