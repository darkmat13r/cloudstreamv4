package com.lagradost.cloudstream4.feature.extensions

import com.lagradost.cloudstream3.APIHolder
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

/**
 * [JvmExtensionRuntime] over a fixture jar built in the test — no network, no committed binary.
 *
 * ## Why `runTest` and `runBlocking` both appear
 *
 * The test bodies use `runTest`. [cleanUp] cannot: `@AfterTest` is not `suspend`, and the cleanup has
 * to actually complete before the next test runs, because the registries it clears
 * (`APIHolder.apis`, `allProviders`, `extractorApis`) are JVM-global singletons in `:library`. A
 * leaked provider would not fail this class — it would fail some later one, elsewhere, for no visible
 * reason.
 *
 * ## What the refusal tests are for
 *
 * Loading an extension executes third-party code in this process. Three of the cases below are
 * nothing but refusals — a lying caller, a hash that does not match, a name that escapes the
 * directory — and they are the point of the class rather than padding: `KMP-11` recorded that `:app`
 * carries `fileHash` through its index and never compares it, so "the check exists" has to be
 * something a test asserts rather than something a KDoc claims.
 */
class JvmExtensionRuntimeTest {

    private lateinit var directory: File
    private lateinit var elsewhere: File
    private lateinit var runtime: JvmExtensionRuntime

    @BeforeTest
    fun setUp() {
        directory = Files.createTempDirectory("extensions").toFile()
        elsewhere = Files.createTempDirectory("elsewhere").toFile()
        runtime = JvmExtensionRuntime(directory)
        FixturePlugin.reset()
    }

    @AfterTest
    fun cleanUp() {
        runBlocking { runtime.loaded().forEach { runtime.unload(it) } }
        directory.deleteRecursively()
        elsewhere.deleteRecursively()
    }

    @Test
    fun `install load unload uninstall is the whole lifecycle`() = runTest {
        val bytes = fixtureJarBytes()
        val listing = listing(jarHash = ExtensionHash.of(bytes))

        val verification = ExtensionHash.verify(bytes, listing.jarHash)
        assertEquals(ExtensionVerification.Verified, verification)

        val installed = runtime.install(listing, bytes, verification).getOrThrow()
        assertEquals(INTERNAL_NAME, installed.internalName)
        assertEquals(File(directory, "$INTERNAL_NAME.jar").canonicalPath, installed.location)
        assertTrue(File(installed.location).isFile)
        // The write went through a `.part` file; it must not still be there.
        assertEquals(listOf("$INTERNAL_NAME.jar"), directory.list()?.sorted())
        assertEquals(listing.jarUrl, installed.sourceUrl)

        runtime.load(installed).getOrThrow()

        // The ordering assertion: BasePlugin.kt:22 stamps sourcePlugin from filename inside load().
        assertEquals(installed.location, FixturePlugin.filenameAtLoad)
        assertEquals(listOf(FixtureApi.PROVIDER_NAME), registeredNames(installed.location))
        assertEquals(listOf(installed), runtime.loaded())

        runtime.unload(installed).getOrThrow()
        assertEquals(1, FixturePlugin.beforeUnloads)
        assertEquals(emptyList(), registeredNames(installed.location))
        assertEquals(emptyList(), runtime.loaded())
        // Unloaded is not uninstalled.
        assertTrue(File(installed.location).isFile)

        runtime.uninstall(installed).getOrThrow()
        assertFalse(File(installed.location).exists())
    }

    /** `PluginManager.kt:639-642` returns success for a plugin it already has; so does this. */
    @Test
    fun `loading twice does not register the providers twice`() = runTest {
        val installed = installFixture()
        runtime.load(installed).getOrThrow()
        runtime.load(installed).getOrThrow()

        assertEquals(1, FixturePlugin.loads)
        assertEquals(listOf(FixtureApi.PROVIDER_NAME), registeredNames(installed.location))
    }

    /** Unloading something that was never loaded is the state the caller asked for, not an error. */
    @Test
    fun `unloading an extension that is not loaded succeeds`() = runTest {
        val installed = installFixture()
        assertTrue(runtime.unload(installed).isSuccess)
        assertEquals(0, FixturePlugin.beforeUnloads)
    }

    /**
     * The defence the class KDoc describes: the runtime recomputes rather than believing a verdict.
     *
     * The caller here passes [ExtensionVerification.Verified] for bytes that are not the published
     * ones — which is exactly what a host with a bug, or one that forgot the check, looks like from
     * in here.
     */
    @Test
    fun `a caller claiming Verified cannot install bytes the jarHash does not match`() = runTest {
        val listing = listing(jarHash = ExtensionHash.of("something else entirely".toByteArray()))

        val result = runtime.install(listing, fixtureJarBytes(), ExtensionVerification.Verified)

        assertTrue(result.isFailure)
        assertEquals(emptyList(), directory.list()?.sorted() ?: emptyList())
    }

    @Test
    fun `a HashMismatch verdict is refused even when the listing carries no hash`() = runTest {
        val mismatch = ExtensionVerification.HashMismatch(expected = "sha256-aa", actual = "sha256-bb")

        val result = runtime.install(listing(jarHash = null), fixtureJarBytes(), mismatch)

        assertTrue(result.isFailure)
        assertEquals(emptyList(), directory.list()?.sorted() ?: emptyList())
    }

    /** `internalName` comes from a third-party index and becomes a path. */
    @Test
    fun `an internalName that is not a safe file name is refused`() = runTest {
        for (name in listOf("../escape", "a/b", "..", ".hidden", "", "with space", "ünïcode")) {
            val result = runtime.install(listing(internalName = name), fixtureJarBytes(), ExtensionVerification.Verified)
            assertTrue(result.isFailure, "internalName='$name' must be refused")
        }
        assertEquals(emptyList(), directory.list()?.sorted() ?: emptyList())
        assertFalse(File(elsewhere, "escape.jar").exists())
    }

    @Test
    fun `load refuses a jar outside the managed directory`() = runTest {
        val outside = File(elsewhere, "$INTERNAL_NAME.jar").also { it.writeBytes(fixtureJarBytes()) }
        val installed = InstalledExtension(
            internalName = INTERNAL_NAME,
            location = outside.canonicalPath,
            version = VERSION,
            isFromRepository = false,
        )

        assertTrue(runtime.load(installed).isFailure)
        assertEquals(0, FixturePlugin.loads)
        assertEquals(emptyList(), registeredNames(outside.canonicalPath))
    }

    /**
     * A jar with classes but no `@CloudstreamPlugin` fails, and the failure is total.
     *
     * The second assertion is the one worth having: a load that throws part-way must not leave a
     * provider registered, because nothing would ever remove it.
     */
    @Test
    fun `a jar with no plugin class fails to load and registers nothing`() = runTest {
        val bytes = jarBytes(FixtureApi::class.java)
        val installed = runtime.install(listing(), bytes, ExtensionVerification.Skipped("fixture")).getOrThrow()

        val result = runtime.load(installed)

        assertTrue(result.isFailure)
        assertEquals(emptyList(), registeredNames(installed.location))
        assertEquals(emptyList(), runtime.loaded())
    }

    @Test
    fun `installing over a loaded extension is refused rather than swapping the jar underneath it`() =
        runTest {
            val installed = installFixture()
            runtime.load(installed).getOrThrow()

            val result = runtime.install(listing(), fixtureJarBytes(), ExtensionVerification.Skipped("fixture"))

            assertTrue(result.isFailure)
            assertEquals(listOf(FixtureApi.PROVIDER_NAME), registeredNames(installed.location))
        }

    @Test
    fun `class discovery reads the class entries and skips META-INF`() {
        val jar = File(directory, "discovery.jar").also { it.writeBytes(fixtureJarBytes()) }

        val names = ExtensionJar.classNames(jar)

        assertEquals(listOf(FixturePlugin::class.java.name), names)
        assertNull(names.firstOrNull { it.contains("META-INF") })
        assertEquals(
            FixturePlugin::class.java.name,
            ExtensionJar.findPluginClass(jar, javaClass.classLoader).name,
        )
    }

    private suspend fun installFixture(): InstalledExtension {
        val bytes = fixtureJarBytes()
        val listing = listing(jarHash = ExtensionHash.of(bytes))
        return runtime.install(listing, bytes, ExtensionHash.verify(bytes, listing.jarHash)).getOrThrow()
    }

    private fun fixtureJarBytes(): ByteArray = jarBytes(FixturePlugin::class.java)

    /**
     * A jar holding [classes]' compiled bytes, plus the two non-class entries a published jar has.
     *
     * `YoutubeProvider.jar` carries a `META-INF/<name>.kotlin_module` and directory entries and **no
     * `META-INF/MANIFEST.MF`** (`unzip -l`, `KMP-13` §2), so the fixture carries the same: a scanner
     * that assumed a manifest, or that mistook a directory entry for a class, would pass against a
     * tidier fixture and fail against a real one.
     */
    private fun jarBytes(vararg classes: Class<*>): ByteArray {
        val target = Files.createTempFile("fixture", ".jar").toFile()
        try {
            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("META-INF/"))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("META-INF/fixture.kotlin_module"))
                zip.write(byteArrayOf(0, 0, 0, 0))
                zip.closeEntry()
                for (type in classes) {
                    val path = type.name.replace('.', '/') + ".class"
                    val bytes = checkNotNull(type.classLoader.getResourceAsStream(path)) {
                        "no compiled bytes for ${type.name} on the test classpath"
                    }.use { it.readBytes() }
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            return target.readBytes()
        } finally {
            target.delete()
        }
    }

    private fun listing(
        internalName: String = INTERNAL_NAME,
        jarHash: String? = null,
    ) = ExtensionListing(
        internalName = internalName,
        name = "Fixture Provider",
        downloadUrl = "https://fixture.invalid/$internalName.cs3",
        status = ExtensionStatus.Ok,
        version = VERSION,
        apiVersion = 1,
        jarUrl = "https://fixture.invalid/$internalName.jar",
        jarHash = jarHash,
        jarFileSizeBytes = null,
    )

    /** Providers currently registered by [filename], by name. Reads the global registry directly. */
    private fun registeredNames(filename: String): List<String> =
        APIHolder.apis.filter { it.sourcePlugin == filename }.map { it.name }

    private companion object {
        const val INTERNAL_NAME = "FixtureProvider"
        const val VERSION = 7
    }
}
