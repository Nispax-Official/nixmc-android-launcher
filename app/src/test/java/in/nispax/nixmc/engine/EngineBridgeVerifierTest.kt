package `in`.nispax.nixmc.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class EngineBridgeVerifierTest {
    @Test
    fun acceptsACompleteAbiAndJavaCompatibleBridge() {
        val root = Files.createTempDirectory("nixmc-bridge").toFile()
        try {
            writeBridgeFixture(root)
            val report = EngineBridgeVerifier.inspect(
                bridgeRoot = root,
                androidAbi = "arm64-v8a",
                javaMajorVersion = 21,
                selectedRendererId = "gl4es"
            )

            assertTrue("Unexpected blockers: ${report.blockers}", report.isReady)
            assertEquals("0.1.0-test", report.bridgeVersion)
            assertEquals(listOf("gl4es", "mobileglues"), report.availableRenderers.map { it.id })
            assertTrue(report.warnings.any { it.contains("trusted") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsUnsupportedAbiAndRenderer() {
        val root = Files.createTempDirectory("nixmc-bridge").toFile()
        try {
            writeBridgeFixture(root)
            val report = EngineBridgeVerifier.inspect(
                bridgeRoot = root,
                androidAbi = "x86_64",
                javaMajorVersion = 21,
                selectedRendererId = "missing-renderer"
            )

            assertFalse(report.isReady)
            assertTrue(report.blockers.any { it.contains("does not support device ABI") })
            assertTrue(report.blockers.any { it.contains("not provided by this bridge") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsModifiedNativeFiles() {
        val root = Files.createTempDirectory("nixmc-bridge").toFile()
        try {
            writeBridgeFixture(root)
            File(root, "lib/arm64-v8a/libnixbridge.so").writeBytes(byteArrayOf(9, 9, 9))

            val report = EngineBridgeVerifier.inspect(root, "arm64-v8a", 21, "gl4es")
            assertFalse(report.isReady)
            assertTrue(report.blockers.any { it.contains("checksum mismatch") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun blocksNativePathsThatEscapeTheBridgeDirectory() {
        val parent = Files.createTempDirectory("nixmc-bridge-parent").toFile()
        val root = File(parent, "bridge").apply { mkdirs() }
        try {
            val outside = File(parent, "outside.so").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val jar = File(root, "classpath/lwjgl-bridge.jar").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(7, 7, 7))
            }
            val manifest = validManifest(
                path = "../outside.so",
                hash = sha256(outside),
                classpathPath = "classpath/lwjgl-bridge.jar",
                classpathHash = sha256(jar)
            )
            File(root, EngineBridgeVerifier.MANIFEST_FILE_NAME).writeText(manifest.toString())

            val report = EngineBridgeVerifier.inspect(root, "arm64-v8a", 21, "gl4es")
            assertFalse(report.isReady)
            assertTrue(report.blockers.any { it.contains("Unsafe native bridge file path") })
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun explainsMissingManifestOrUnverifiedRuntime() {
        val root = Files.createTempDirectory("nixmc-bridge").toFile()
        try {
            val noManifest = EngineBridgeVerifier.inspect(root, "arm64-v8a", null)
            assertFalse(noManifest.isReady)
            assertTrue(noManifest.blockers.any { it.contains("manifest.json is missing") })
            assertTrue(noManifest.blockers.any { it.contains("Java runtime has not passed") })

            writeBridgeFixture(root)
            val noRuntime = EngineBridgeVerifier.inspect(root, "arm64-v8a", null, "gl4es")
            assertFalse(noRuntime.isReady)
            assertTrue(noRuntime.blockers.any { it.contains("Java runtime has not passed") })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeBridgeFixture(root: File) {
        val native = File(root, "lib/arm64-v8a/libnixbridge.so").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(3, 1, 4, 1, 5, 9))
        }
        val jar = File(root, "classpath/lwjgl-bridge.jar").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(2, 7, 1, 8))
        }
        File(root, EngineBridgeVerifier.MANIFEST_FILE_NAME)
            .writeText(
                validManifest(
                    "lib/arm64-v8a/libnixbridge.so",
                    sha256(native),
                    "classpath/lwjgl-bridge.jar",
                    sha256(jar)
                ).toString()
            )
    }

    private fun validManifest(
        path: String,
        hash: String,
        classpathPath: String,
        classpathHash: String
    ) = JSONObject()
        .put("schemaVersion", 1)
        .put("bridgeVersion", "0.1.0-test")
        .put("androidAbis", JSONArray().put("arm64-v8a"))
        .put("minimumJavaMajor", 21)
        .put("maximumJavaMajor", 21)
        .put("launchEntry", JSONObject()
            .put("class", "com.oracle.dalvik.VMLauncher")
            .put("method", "launchJVM")
            .put("library", path))
        .put("nativeLoadOrder", JSONArray().put(path))
        .put("classpathEntries", JSONArray().put(classpathPath))
        .put("files", JSONArray()
            .put(JSONObject().put("path", path).put("sha256", hash))
            .put(JSONObject().put("path", classpathPath).put("sha256", classpathHash)))
        .put("renderers", JSONArray()
            .put(JSONObject().put("id", "gl4es").put("name", "Compatibility"))
            .put(JSONObject().put("id", "mobileglues").put("name", "Extended OpenGL")))

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val bytes = ByteArray(8192)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
