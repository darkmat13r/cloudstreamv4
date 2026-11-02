package com.lagradost.cloudstream4.feature.extensions.sandbox

import android.net.Uri
import android.util.Base64
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class AndroidShimTest {

    @Test
    fun `base64 matches Android's flags`() {
        val bytes = "hello?>".toByteArray()
        assertEquals("aGVsbG8/Pg==\n", Base64.encodeToString(bytes, Base64.DEFAULT))
        assertEquals("aGVsbG8/Pg==", Base64.encodeToString(bytes, Base64.NO_WRAP))
        assertEquals("aGVsbG8_Pg==", Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE))
        assertEquals("aGVsbG8/Pg", Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING))
        // DEFAULT wraps at 76 columns; the JDK's plain encoder does not.
        val long = Base64.encodeToString(ByteArray(100), Base64.DEFAULT)
        assertEquals(76, long.substringBefore('\n').length)
    }

    @Test
    fun `base64 decodes loosely, as extensions rely on`() {
        val expected = "hello?>".toByteArray()
        listOf("aGVsbG8/Pg==", "aGVsbG8/Pg", "aGVsbG8/\nPg==\n").forEach {
            assertContentEquals(expected, Base64.decode(it, Base64.DEFAULT), it)
        }
        assertContentEquals(expected, Base64.decode("aGVsbG8_Pg", Base64.URL_SAFE))
    }

    @Test
    fun `uri reads the parts extensions ask for and never throws`() {
        val uri = Uri.parse("https://a.example:8443/x/y.m3u8?q=a%20b&r=1#f")
        assertEquals("https", uri.getScheme())
        assertEquals("a.example", uri.getHost())
        assertEquals(8443, uri.getPort())
        assertEquals("y.m3u8", uri.getLastPathSegment())
        assertEquals("a b", uri.getQueryParameter("q"))
        assertNull(uri.getQueryParameter("missing"))
        assertEquals("not a uri %%%", Uri.parse("not a uri %%%").toString())
        assertNull(Uri.parse("not a uri %%%").getHost())
        assertEquals("a%20b", Uri.encode("a b"))
    }

    @Test
    fun `an Android-style plugin loads in the sandbox and its preferences persist across runs`() {
        val launcher = HostLauncher.forThisProcess() ?: return
        val root = File.createTempFile("android-style", "").apply { delete(); mkdirs() }.canonicalFile
        try {
            val jar = File(root, "store/android.jar").apply { parentFile.mkdirs() }
            JarOutputStream(jar.outputStream()).use { out ->
                listOf(AndroidStylePlugin::class.java, AndroidStyleApi::class.java).forEach { type ->
                    val path = type.name.replace('.', '/') + ".class"
                    out.putNextEntry(JarEntry(path))
                    type.classLoader.getResourceAsStream(path)!!.use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
            val data = File(root, "data").apply { mkdirs() }
            suspend fun run(): String {
                val (host, providers) = PluginHostClient.start(launcher, jar, data, "android")
                try {
                    providers.getOrElse { throw AssertionError(host.hostOutput, it) }
                    return host.search("AndroidStyle", "query", 1).items.single().name
                } finally {
                    host.close()
                }
            }
            runBlocking {
                assertEquals("query|example.invalid|run=1", run())
                assertEquals("query|example.invalid|run=2", run())
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
