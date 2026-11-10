package com.lagradost.cloudstream4.feature.extensions.sandbox

import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * `KMP-23`: Gson as a plugin meets it, and what it does and does not add.
 *
 * The plugins are compiled here, from source held in strings, into jars that are **not** on this test's
 * classpath. A host loads parent-first, so a fixture on the classpath would be defined by the app's own
 * loader and would prove nothing about a plugin's (`KMP-18` was caught by exactly that). Each reports what it
 * measured as its provider's name, from a real host under real Seatbelt.
 */
class GsonInHostTest {
    private lateinit var root: File

    @BeforeTest
    fun setUp() {
        root = File.createTempFile("gson-host", "").apply { delete(); mkdirs() }.canonicalFile
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    /** Compiles a plugin whose `load()` body sets `String report`, into `store/<name>.jar`. */
    private fun pluginJar(name: String, body: String, members: String = ""): File {
        val source = File(root, "$name/src/fixture/$name/Fixture.java").apply { parentFile.mkdirs() }
        source.writeText(
            """
            package fixture.$name;
            @com.lagradost.cloudstream3.plugins.CloudstreamPlugin
            public class Fixture extends com.lagradost.cloudstream3.plugins.BasePlugin {
                $members
                @Override public void load() throws Throwable {
                    String report;
                    $body
                    com.lagradost.cloudstream3.MainAPI api = new com.lagradost.cloudstream3.MainAPI() {};
                    api.setName(report);
                    registerMainAPI(api);
                }
            }
            """.trimIndent()
        )
        val classes = File(root, "$name/classes").apply { mkdirs() }
        val compiler = assertNotNull(ToolProvider.getSystemJavaCompiler(), "tests need a JDK")
        val code = compiler.run(
            null, null, null,
            "-proc:none", "-nowarn", "-cp", System.getProperty("java.class.path"), "-d", classes.path, source.path,
        )
        assertEquals(0, code, "the fixture did not compile")
        assertNull(javaClass.classLoader.getResource("fixture/$name/Fixture.class"), "a fixture on the classpath proves nothing")

        val jar = File(root, "$name/store/$name.jar").apply { parentFile.mkdirs() }
        JarOutputStream(jar.outputStream()).use { out ->
            classes.walkTopDown().filter { it.isFile }.forEach { file ->
                out.putNextEntry(JarEntry(file.relativeTo(classes).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return jar
    }

    /** What the plugin in [jar] reported, or `null` where this platform has no sandbox. */
    private fun reportOf(jar: File): String? {
        val launcher = HostLauncher.forThisProcess() ?: return null
        return runBlocking {
            val (host, providers) = PluginHostClient.start(launcher, jar, File(jar.parentFile.parentFile, "data"), "gson")
            try {
                providers.getOrElse { throw AssertionError("host: ${host.redactedLog}", it) }.single().name
            } finally {
                host.close()
            }
        }
    }

    private val usesGson by lazy {
        pluginJar(
            name = "uses",
            members = """
                public static class Dto {
                    private int value;
                    public Dto(int unused) { throw new IllegalStateException("constructor ran"); }
                }
            """,
            body = """
                com.google.gson.Gson gson = new com.google.gson.Gson();
                // Text cannot choose the type: Gson has no type ids, so these two keys are just unknown keys.
                Dto dto = gson.fromJson("{\"value\":42,\"@class\":\"java.lang.ProcessBuilder\",\"class\":\"java.lang.Runtime\"}", Dto.class);
                String written = gson.toJson(dto);
                String jdk;
                try { gson.fromJson("{}", ProcessBuilder.class); jdk = "built"; }
                catch (com.google.gson.JsonParseException refused) { jdk = "refused"; }
                String depth;
                try { gson.fromJson("[".repeat(100000), Object.class); depth = "parsed"; }
                catch (com.google.gson.JsonParseException refused) { depth = "refused"; }
                report = "read " + dto.value + " wrote " + written.length() + " jdk " + jdk + " depth " + depth;
            """,
        )
    }

    @Test
    fun `a plugin outside the classpath reaches Gson under the sandbox, and what it can do with it`() {
        val report = reportOf(usesGson) ?: return
        // read 42: a private field was set on a class whose only constructor throws, so this is Gson's
        // Unsafe path, working inside the sandbox on the bundle's own `jdk.unsupported`.
        // wrote 12: `{"value":42}`, a private field read straight back.
        // jdk refused: the module system stops Gson at java.base, because the host has no `--add-opens`.
        // depth refused: 100,000 open brackets end at Gson's nesting limit, not in a StackOverflowError.
        assertEquals("read 42 wrote 12 jdk refused depth refused", report)
    }

    /**
     * Pins the gap the `KMP-23` record states rather than hiding it: Gson does through its public API what
     * `java/lang/reflect` is warned for, and no rule could tell one call to `fromJson` from another.
     */
    @Test
    fun `the scanner has nothing to say about a plugin using Gson, not even its private access`() {
        val jar = pluginJar(
            name = "clean",
            members = """
                public static class Dto {
                    private int value;
                    public Dto(int unused) { throw new IllegalStateException("constructor ran"); }
                }
            """,
            body = """
                com.google.gson.Gson gson = new com.google.gson.Gson();
                Dto dto = gson.fromJson("{\"value\":42}", Dto.class);
                report = "read " + dto.value + " wrote " + gson.toJson(dto).length();
            """,
        )
        val report = ExtensionScanner.scan(jar)
        assertEquals(emptyList(), report.findings)
        assertTrue("com/google/gson/Gson" in report.externalClasses)
    }

    /**
     * The control, with no Gson in it. What `fromJson` did above was already open to any plugin, so Gson adds
     * no capability, only one the scanner does not see. The reflective route gets a warning and is not refused.
     */
    @Test
    fun `allocating without a constructor was already possible, and warned about, without Gson`() {
        val jar = pluginJar(
            name = "reflective",
            members = """
                public static class Dto {
                    public int value = 1;
                    public Dto(int unused) { throw new IllegalStateException("constructor ran"); }
                }
            """,
            body = """
                String owner = "sun.misc.";
                owner = owner + "Unsafe";
                java.lang.reflect.Field field = Class.forName(owner).getDeclaredField("theUnsafe");
                field.setAccessible(true);
                Object unsafe = field.get(null);
                Object made = unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, Dto.class);
                report = "allocated " + ((Dto) made).value;
            """,
        )
        val scan = ExtensionScanner.scan(jar)
        assertFalse(scan.blocked, scan.findings.toString())
        assertTrue(scan.findings.any { it.rule == "reflection" }, scan.findings.toString())

        val report = reportOf(jar) ?: return
        // 0, not 1: no constructor and no field initialiser ran.
        assertEquals("allocated 0", report)
    }

    /** The host's copy is outside any scan, so a plugin carrying its own is still judged as plugin code. */
    @Test
    fun `a plugin bundling its own copy of Gson is refused, which the host's copy does not change`() {
        val location = Class.forName("com.google.gson.Gson").protectionDomain.codeSource.location
        val report = ExtensionScanner.scan(File(location.toURI()))
        assertTrue(report.findings.any { it.severity == ScanSeverity.Block && "sun.misc.Unsafe" in it.detail }, report.findings.toString())
    }
}
