package com.lagradost.cloudstream4.feature.extensions.sandbox

import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * A real dex through the real converter, in the real no-network sandbox, then run in a real host.
 * `KMP-14` could not commit a dex fixture without a binary; this is one of our own, as text.
 * The source is `src/jvmTest/conversionFixture/ConversionFixture.kt`, deliberately not compiled here.
 */
class DexConversionTest {
    private lateinit var root: File

    @BeforeTest
    fun setUp() {
        root = File.createTempFile("conversion", "").apply { delete(); mkdirs() }.canonicalFile
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun convert(launcher: HostLauncher): File {
        val text = javaClass.getResourceAsStream("/conversion-fixture.dex.b64")!!.use { it.readBytes() }.decodeToString()
        val dex = Base64.getMimeDecoder().decode(text.trim())
        assertTrue(String(dex, 4, 3).toInt() >= 37, "a fixture below 037 proves nothing about stack maps")
        val cs3 = File(root, "fixture.cs3")
        ZipOutputStream(cs3.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("classes.dex"))
            out.write(dex)
            out.closeEntry()
        }
        val store = File(root, "store")
        val jar = File(store, "fixture.jar")
        val failure = runBlocking { PluginHostClient.convert(launcher, cs3, jar, store) }
        assertNull(failure, failure)
        return jar
    }

    private fun classesOf(jar: File): Map<String, ByteArray> = ZipFile(jar).use { zip ->
        zip.entries().asSequence().filter { it.name.endsWith(".class") }
            .associate { it.name to zip.getInputStream(it).readBytes() }
    }

    private companion object {
        const val FIXTURE_PACKAGE = "com/lagradost/cloudstream4/feature/extensions/sandbox"

        /** `this` and `flag`. The slot after them is the one the branches share. */
        const val ANSWER_ARGUMENTS = 2
    }

    @Test
    fun `a dex keeps its real method names`() {
        val launcher = HostLauncher.forThisProcess() ?: return
        val refs = classesOf(convert(launcher)).values.flatMap { ClassFacts.read(it).methods }
        val onResult = refs.filter { (owner, _) -> owner == "kotlin/Result" }.map { it.second }.toSet()
        assertTrue("constructor-impl" in onResult, onResult.toString())
        assertFalse(onResult.any { "_" in it }, "rewritten: $onResult")
    }

    @Test
    fun `a recent dex comes out with stack maps, so the JVM will verify it`() {
        val launcher = HostLauncher.forThisProcess() ?: return
        val classes = classesOf(convert(launcher))
        assertTrue(classes.isNotEmpty())
        classes.forEach { (name, bytes) ->
            val major = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
            assertEquals(52, major, name)
        }
        assertTrue(classes.values.any { String(it, Charsets.ISO_8859_1).contains("StackMapTable") })
    }

    @Test
    fun `the converted plugin passes the JVM's verifier and calls kotlin's Result, in a real host`() {
        val launcher = HostLauncher.forThisProcess() ?: return
        val jar = convert(launcher)
        assertFalse(ExtensionScanner.scan(jar).blocked)
        // On the classpath, the host would run that copy and this test would prove nothing.
        assertNull(javaClass.classLoader.getResource("$FIXTURE_PACKAGE/ConversionFixtureApi.class"))
        assertTrue(ZipFile(jar).use { it.getEntry("$FIXTURE_PACKAGE/ConversionFixtureApi.class") != null })
        runBlocking {
            val (host, providers) = PluginHostClient.start(launcher, jar, File(root, "data"), "fixture")
            try {
                providers.getOrElse { throw AssertionError(host.hostOutput, it) }
                // The three answers are three branches, and "failed" is the path through `runCatching`.
                assertEquals("failed", host.search("ConversionFixture", "not a number", 1).items.single().name)
                assertEquals("big", host.search("ConversionFixture", "7", 1).items.single().name)
                assertEquals("small", host.search("ConversionFixture", "0", 1).items.single().name)
            } finally {
                host.close()
            }
        }
    }

    /** Each runs in its own host, so a class the verifier refuses fails its own query and no other. */
    private fun answers(launcher: HostLauncher, jar: File, query: String): List<String> = runBlocking {
        val (host, providers) = PluginHostClient.start(launcher, jar, File(root, "data"), "fixture")
        try {
            providers.getOrElse { throw AssertionError(host.hostOutput, it) }
            listOf(1, 2).map { page -> host.search("ConversionFixture", query, page).items.single().name }
        } finally {
            host.close()
        }
    }

    /** What `answer`'s stack maps say is in the slot its two branches share, as the JVM will read it. */
    private fun joinedTypes(jar: File, fixture: String): List<String> {
        val bytes = classesOf(jar).getValue("$FIXTURE_PACKAGE/$fixture.class")
        val types = mutableListOf<String>()
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                if (name != "answer") return null
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitFrame(type: Int, numLocal: Int, local: Array<out Any?>?, numStack: Int, stack: Array<out Any?>?) {
                        local.orEmpty().take(numLocal).drop(ANSWER_ARGUMENTS).filterIsInstance<String>().forEach { types += it }
                    }
                }
            }
        }, ClassReader.EXPAND_FRAMES)
        return types
    }

    @Test
    fun `no stack map names the class dex2jar makes up`() {
        val launcher = HostLauncher.forThisProcess() ?: return
        val classes = classesOf(convert(launcher))
        val named = classes.filterValues { String(it, Charsets.ISO_8859_1).contains("java/util/Object") }.keys
        assertTrue(named.isEmpty(), "$named")
    }

    @Test
    fun `a class nobody has, joined with a host class, verifies and runs in a real host`() {
        val launcher = HostLauncher.forThisProcess() ?: return
        val jar = convert(launcher)
        assertNull(javaClass.classLoader.getResource("$FIXTURE_PACKAGE/AbsentFromEverything.class"))
        assertTrue(ZipFile(jar).use { it.getEntry("$FIXTURE_PACKAGE/AbsentFromEverything.class") == null })
        assertEquals(listOf("null null ConversionFixtureMissing", "StringBuilder StringBuilder ConversionFixtureMissing"), answers(launcher, jar, "missing"))
        assertEquals(listOf("java/lang/Object"), joinedTypes(jar, "ConversionFixtureMissing"))
    }

    @Test
    fun `two plugin classes join at the host class above both`() {
        val launcher = HostLauncher.forThisProcess() ?: return
        val jar = convert(launcher)
        assertEquals(listOf("java/lang/RuntimeException"), joinedTypes(jar, "ConversionFixtureHostAncestor"))
        assertEquals("ConversionFixtureStateError ConversionFixtureStateError ConversionFixtureHostAncestor", answers(launcher, jar, "host-ancestor").first())
    }

    @Test
    fun `a plugin class joined with its plugin parent is that parent, either way round`() {
        val launcher = HostLauncher.forThisProcess() ?: return
        val jar = convert(launcher)
        val base = "$FIXTURE_PACKAGE/ConversionFixtureBase"
        assertEquals(listOf(base), joinedTypes(jar, "ConversionFixtureChildFirst"))
        assertEquals(listOf(base), joinedTypes(jar, "ConversionFixtureParentFirst"))
        assertEquals("ConversionFixtureChild ConversionFixtureChild ConversionFixtureChildFirst", answers(launcher, jar, "child-first").first())
        assertEquals("ConversionFixtureBase ConversionFixtureBase ConversionFixtureParentFirst", answers(launcher, jar, "parent-first").first())
    }

    @Test
    fun `a provider that builds a SyncRepo loads, and nobody is signed in`() {
        val launcher = HostLauncher.forThisProcess() ?: return
        val signedOut = "signed in false, library null"
        assertEquals(listOf(signedOut, signedOut), answers(launcher, convert(launcher), "sync"))
    }
}
